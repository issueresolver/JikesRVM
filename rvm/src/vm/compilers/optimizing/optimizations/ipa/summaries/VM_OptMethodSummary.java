/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$
package com.ibm.JikesRVM.opt;
import com.ibm.JikesRVM.*;

/**
 * Compute a simple, fast, intraprocedural summary of a method
 * via a single pass over its bytecode array.
 *
 * <p> <em> IMPORTANT: </em>
 *   <ul>
 *   <li> (1) Summaries of this form are computed for every method in the 
 *        system (including those in the bootimage) 
 *        therefore space is an issue and we must
 *        be able to store the resulting summaries in the bootimage.
 *   <li> (2) VM_OptMethodSummary.summarizeMethod is called from 
 *	   VM_Method.readAttributes
 * 	    when RVM_WITH_OPT_COMPILER is true. Therefore this class must 
 *  	    be in the bootimage
 * 	    and all classes it references must be in the bootimage as well.
 *   <li> (3) The VM prefix on the class name gets us into the 
 *	  bootimage (when RVM_WITH_OPT_COMPILER is true).
 *   </ul> 
 * 
 * @author Dave Grove
 * @author Stephen Fink
 */
public final class VM_OptMethodSummary implements VM_BytecodeConstants {
  // Estimates of the size costs of various classes of bytecodes.
  // NOTE: This estimates are meant to reflect relative average costs, 
  // of the generated machince code, taking into account the 
  // typical optimizations 
  // we can perform (e.g. most null pointer checks, 
  // many array bounds checks eliminated).
  public static final int SIMPLE_OPERATION_COST = 1;
  public static final int LONG_OPERATION_COST = 2;
  public static final int ARRAY_LOAD_COST = 2;
  public static final int ARRAY_STORE_COST = 2;
  public static final int JSR_COST = 5;
  public static final int CALL_COST = 6;
  // Bias to inlining methods with magic
  // most magics are quite cheap (0-1 instructions)
  public static final int MAGIC_COST = 0;
  // News are actually more expensive than calls
  // but bias to inline methods that allocate
  // objects becuase we expect better downstream optimization of 
  // the caller due to class analysis
  // and propagation of nonNullness
  public static final int ALLOCATION_COST = 4;
  // Approximations, assuming some CSE/PRE of object model computations
  public static final int CLASS_CHECK_COST = 2*SIMPLE_OPERATION_COST;
  public static final int STORE_CHECK_COST = 4*SIMPLE_OPERATION_COST;
  // Just a call.
  public static final int THROW_COST = CALL_COST;
  // Really a bunch of operations plus a call, but undercharge because
  // we don't have worry about this causing an exponential growth of call chain
  // and we probably want to inline synchronization 
  // (to get a chance to optimize it).
  public static final int SYNCH_COST = 4*SIMPLE_OPERATION_COST;
  // Switch operations are very likely to be quite expensive
  public static final int SWITCH_COST = 20;

  //////
  // Simple summary information is computed during loading for
  // every VM_Method that has a bytecode attribute.
  // The following methods access the summary.
  //////

  /** An estimate of the expected size of the machine code instructions 
   * that will be generated by the opt compiler if the method is inlined.
   */
  public static final int inlinedSizeEstimate(VM_Method method) {
    return getSize(getSummary(method));
  }

  /**
   * returns true if the method contains a VM_Magic.xxx
   */
  public static final boolean hasMagic(VM_Method method) {
    return hasMagic(getSummary(method));
  }

  /**
   * returns true if the method contains a monitorenter or monitortexit bytecode
   */
  public static final boolean hasSynch(VM_Method method) {
    return hasSynch(getSummary(method));
  }

  /**
   * returns true if the method contains array loads and/or stores
   */
  public static final boolean hasArrayOp(VM_Method method) {
    return hasArrayOp(getSummary(method));
  }

  /**
   * returns true if the method contains an allocation 
   * (new, newarray, anewarray, multianewarray)
   */
  public static final boolean hasAllocation(VM_Method method) {
    return hasAllocation(getSummary(method));
  }

  /**
   * returns true if the method contains a throw bytecode
   */
  public static final boolean hasThrow(VM_Method method) {
    return hasThrow(getSummary(method));
  }

  /**
   * returns true if the method contains an invokexxx bytecode
   */
  public static final boolean hasInvoke(VM_Method method) {
    return hasInvoke(getSummary(method));
  }

  // returns true if the method contains getfield/putfield/getstatic/putstatic
  public static final boolean hasFieldOp(VM_Method method) {
    return hasFieldOp(getSummary(method));
  }

  /**
   * returns true if the method may write to a given field
   */
  public static final boolean mayWrite(VM_Method method, VM_Field field) {
    // TODO: what's the proper behavior in this case??  For now, be
    // conservative.
    if (method.getBytecodes() == null)
      return true;
    VM_Field[] summary = getWriteSummary(method);
    if (summary == null) return false;
    for (int i=0; i<summary.length; i++) {
      if (summary[i] == field) return true;
    } 
    return false;
  }

  /**
   * This method computes and stores a  
   * summary of interesting method characteristics.
   * After a summary has been computed for a VM_Method, 
   * the methods defined above
   * can be used to querry attributes.
   *
   * @param meth the VM_Method to be summarized
   * @param bytecodes the bytecode array to be summarized
   * @param isSynchronized does the VM_Method represent a synchronized method?
   */
  public static void summarizeMethod(VM_Method method, byte[] bytecodes, 
				      boolean isSynchronized) {
    computeSummary(method, bytecodes, isSynchronized);
  }

  /**
   * Backing store for simple bytecode summaries.
   */
  private static int[] summaries = new int[8000];

  /**
   * Backing store for summaries of fields written.
   */
  private static VM_Field[][] writeSets = new VM_Field[8000][];

  /**
   * Store the simple summary for a given method
   */
  private static void storeSummary(VM_Method method, int summary) {
    int idx = method.getDictionaryId();
    if (idx >= summaries.length) {
      int newLength = summaries.length*2;
      if (idx >= newLength)
        newLength = idx;
      int[] newarray = new int[newLength];
      for (int i = 0, n = summaries.length; i < n; ++i)
        newarray[i] = summaries[i];
      summaries = newarray;
    }
    summaries[idx] = summary;
  }

  /**
   * Store the summary of fields written for a given method
   */
  private static void storeWriteSummary(VM_Method method, VM_Field [] set) {
    int idx = method.getDictionaryId();
    if (idx >= writeSets.length) {
      int newLength = writeSets.length*2;
      if (idx >= newLength)
        newLength = idx;
      VM_Field[][] newarray = new VM_Field[newLength][];
      for (int i = 0, n = writeSets.length; i < n; ++i)
        newarray[i] = writeSets[i];
      writeSets = newarray;
    }
    writeSets[idx] = set;
  }

  /**
   * Return the simple bytecode summary for a given method
   */
  private static int getSummary(VM_Method method) {
    int idx = method.getDictionaryId();
    if (VM.VerifyAssertions) {
      VM._assert(method.getBytecodes() != null);
      VM._assert(isValid(summaries[idx]));
    }
    return summaries[idx];
  }

  /**
   * Return the summary of fields written for a given method
   * @return the set of VM_Fields the method writes to
   */
  private static VM_Field[] getWriteSummary(VM_Method method) {
    int idx = method.getDictionaryId();
    if (VM.VerifyAssertions) {
      VM._assert(method.getBytecodes() != null);
      VM._assert(isValid(summaries[idx]));
    }
    return writeSets[idx];
  }

  private static final boolean DEBUG = false;
  private static final boolean VERBOSE = false;
  // Summary format: vfff ffff ffff ffff ssss ssss ssss ssss
  // v = validity bit
  // f = 15 bits of flags
  // s = 16 bits of size estimate
  private static final int VALID_MASK = 0x80000000;
  private static final int FLAG_MASK = 0x7fff0000;
  private static final int SIZE_MASK = 0x0000ffff;
  // Definition of flag bits
  private static final int HAS_MAGIC      = 0x10000000;
  private static final int HAS_SYNCH      = 0x20000000;
  private static final int HAS_ARRAY_OP   = 0x40000000;
  private static final int HAS_ALLOCATION = 0x80000000;
  private static final int HAS_THROW      = 0x01000000;
  private static final int HAS_INVOKE     = 0x02000000;
  private static final int HAS_FIELD_OP   = 0x04000000;

  /**
   * Is the summary valid?
   */
  private static boolean isValid(int s) {
    return (s & VALID_MASK) != 0;
  }

  private static int getSize(int s) {
    return (s & SIZE_MASK);
  }

  private static boolean hasMagic(int s) {
    return (s & HAS_MAGIC) != 0;
  }

  private static boolean hasSynch(int s) {
    return (s & HAS_SYNCH) != 0;
  }

  private static boolean hasArrayOp(int s) {
    return (s & HAS_ARRAY_OP) != 0;
  }

  private static boolean hasAllocation(int s) {
    return (s & HAS_ALLOCATION) != 0;
  }

  private static boolean hasThrow(int s) {
    return (s & HAS_THROW) != 0;
  }

  private static boolean hasInvoke(int s) {
    return (s & HAS_INVOKE) != 0;
  }

  private static boolean hasFieldOp(int s) {
    return (s & HAS_FIELD_OP) != 0;
  }

  private static int setValid(int s) {
    return s | VALID_MASK;
  }

  private static int setSize(int s, int size) {
    if (VM.VerifyAssertions)
      VM._assert(size >= 0);
    if (size > SIZE_MASK)
      return s |= SIZE_MASK; 
    else 
      return s |= (size);
  }

  private static int setMagic(int s) {
    return s | HAS_MAGIC;
  }

  private static int setSynch(int s) {
    return s | HAS_SYNCH;
  }

  private static int setArrayOp(int s) {
    return s | HAS_ARRAY_OP;
  }

  private static int setAllocation(int s) {
    return s | HAS_ALLOCATION;
  }

  private static int setThrow(int s) {
    return s | HAS_THROW;
  }

  private static int setInvoke(int s) {
    return s | HAS_INVOKE;
  }

  private static int setFieldOp(int s) {
    return s | HAS_FIELD_OP;
  }

  /**
   * This method computes a summary of interesting method characteristics 
   * and stores itreturns an encoding of the summary as an int.
   * 
   * @param method the VM_Method to be summarized
   * @param bytecodes the bytecode array to be summarized
   * @param isSynchronized does the VM_Method represent a synchronized method?
   * @return an int encoding the summary
   */
  private static void computeSummary(VM_Method method, byte[] bytecodes, 
				     boolean isSynchronized) {
    int calleeSize = 0;
    int bcIndex = 0;
    int bcLength = bytecodes.length;
    int nBytecodes = 0;
    int summary = VALID_MASK;
    VM_FieldVector written = new VM_FieldVector();
    VM_Class klass = method.getDeclaringClass();
    if (VERBOSE) {
      VM.sysWrite("Summarizing method ");
      VM.sysWrite(method);
      VM.sysWrite("\n");
    }
    if (isSynchronized) {
      summary = setSynch(summary);
      calleeSize += 2*SYNCH_COST;    // NOTE: ignoring catch/unlock/rethrow block.  Probably the right thing to do.
    }
    while (bcIndex < bytecodes.length) {
      int code = (int)(bytecodes[bcIndex++] & 0xFF);
      if (VERBOSE) {
        VM.sysWrite("\tcurrent cost = ");
        VM.sysWrite(calleeSize, false);
        VM.sysWrite("\n\tcurrent bytecode = ");
        VM.sysWrite(code);
        VM.sysWrite("\n");
      }
      nBytecodes++;
      switch (code) {
        // 0 cost
        case JBC_nop:
          break;
          // 0 cost.  Most should go away with good register 
          // allocation/instruction selection;
          // some might stick around, but should be cheap (register moves).
          // May want to increase cost for float/double/long constants 
          // these usually 
          // take more instructions to implement.
        case JBC_aconst_null:case JBC_iconst_m1:case JBC_iconst_0:
        case JBC_iconst_1:case JBC_iconst_2:case JBC_iconst_3:
        case JBC_iconst_4:case JBC_iconst_5:case JBC_lconst_0:
        case JBC_lconst_1:case JBC_fconst_0:case JBC_fconst_1:
        case JBC_fconst_2:case JBC_dconst_0:case JBC_dconst_1:
        case JBC_iload_0:case JBC_iload_1:case JBC_iload_2:
        case JBC_iload_3:case JBC_lload_0:case JBC_lload_1:
        case JBC_lload_2:case JBC_lload_3:case JBC_fload_0:
        case JBC_fload_1:case JBC_fload_2:case JBC_fload_3:
        case JBC_dload_0:case JBC_dload_1:case JBC_dload_2:
        case JBC_dload_3:case JBC_aload_0:case JBC_aload_1:
        case JBC_aload_2:case JBC_aload_3:
          break;
        case JBC_bipush:case JBC_ldc:case JBC_iload:case JBC_lload:
        case JBC_fload:case JBC_dload:case JBC_aload:
          bcIndex += 1;
          break;
        case JBC_sipush:case JBC_ldc_w:case JBC_ldc2_w:
          bcIndex += 2;
          break;
          // Array loads: null check, bounds check, index computation, load
        case JBC_iaload:case JBC_laload:case JBC_faload:case JBC_daload:
        case JBC_aaload:case JBC_baload:case JBC_caload:case JBC_saload:
          summary = setArrayOp(summary);
          calleeSize += ARRAY_LOAD_COST;
          break;
          // 0 cost.  Most should go away with good register allocation,
        case JBC_istore:case JBC_lstore:case JBC_fstore:case JBC_dstore:
        case JBC_astore:
          bcIndex += 1;
          break;
        case JBC_istore_0:case JBC_istore_1:case JBC_istore_2:
        case JBC_istore_3:case JBC_lstore_0:case JBC_lstore_1:
        case JBC_lstore_2:case JBC_lstore_3:case JBC_fstore_0:
        case JBC_fstore_1:case JBC_fstore_2:case JBC_fstore_3:
        case JBC_dstore_0:case JBC_dstore_1:case JBC_dstore_2:
        case JBC_dstore_3:case JBC_astore_0:case JBC_astore_1:
        case JBC_astore_2:case JBC_astore_3:
          break;
          // Array stores: null check, bounds check, index computation, load
        case JBC_iastore:case JBC_lastore:case JBC_fastore:
        case JBC_dastore:case JBC_bastore:case JBC_castore:case JBC_sastore:
          summary = setArrayOp(summary);
          calleeSize += ARRAY_STORE_COST;
          break;
        case JBC_aastore:
          summary = setArrayOp(summary);
          calleeSize += ARRAY_STORE_COST + STORE_CHECK_COST;
          break;
          // 0 cost. Most should go away with good register allocation.
        case JBC_pop:case JBC_pop2:case JBC_dup:case JBC_dup_x1:
        case JBC_dup_x2:case JBC_dup2:case JBC_dup2_x1:case JBC_dup2_x2:
        case JBC_swap:
          break;
          // primitive computations (likely to be very cheap)
        case JBC_iadd:case JBC_fadd:case JBC_dadd:case JBC_isub:
        case JBC_fsub:case JBC_dsub:case JBC_imul:case JBC_fmul:
        case JBC_dmul:case JBC_idiv:case JBC_fdiv:case JBC_ddiv:
        case JBC_irem:case JBC_frem:case JBC_drem:case JBC_ineg:
        case JBC_fneg:case JBC_dneg:case JBC_ishl:case JBC_ishr:
        case JBC_lshr:case JBC_iushr:case JBC_iand:case JBC_ior:case JBC_ixor:
          calleeSize += SIMPLE_OPERATION_COST;
          break;
          // long computations may be different cost than primitive computations
        case JBC_ladd:case JBC_lsub:case JBC_lmul:case JBC_ldiv:
        case JBC_lrem:case JBC_lneg:case JBC_lshl:case JBC_lushr:
        case JBC_land:case JBC_lor:case JBC_lxor:
          calleeSize += LONG_OPERATION_COST;
          break;
          // May be a noop or a prim computation, guess prim is most likely
        case JBC_iinc:
          bcIndex += 2;
          calleeSize += SIMPLE_OPERATION_COST;
          break;
          // Some conversion operations are very cheap
        case JBC_int2byte:case JBC_int2char:case JBC_int2short:
          calleeSize += SIMPLE_OPERATION_COST;
          break;
          // Others are a little more costly
        case JBC_i2l:case JBC_l2i:
          calleeSize += LONG_OPERATION_COST;
          break;
          // Most are roughly as expensive as a call
        case JBC_i2f:case JBC_i2d:case JBC_l2f:case JBC_l2d:
        case JBC_f2i:case JBC_f2l:case JBC_f2d:case JBC_d2i:
        case JBC_d2l:case JBC_d2f:
          calleeSize += CALL_COST;
          break;
          // approximate compares as 1 simple operation
        case JBC_lcmp:case JBC_fcmpl:case JBC_fcmpg:case JBC_dcmpl:
        case JBC_dcmpg:
          calleeSize += SIMPLE_OPERATION_COST;
          break;
          // most control flow is cheap; jsr is more expensive
        case JBC_ifeq:case JBC_ifne:case JBC_iflt:case JBC_ifge:
        case JBC_ifgt:case JBC_ifle:case JBC_if_icmpeq:case JBC_if_icmpne:
        case JBC_if_icmplt:case JBC_if_icmpge:case JBC_if_icmpgt:
        case JBC_if_icmple:case JBC_if_acmpeq:case JBC_if_acmpne:
        case JBC_ifnull:case JBC_ifnonnull:
          bcIndex += 2;
          calleeSize += SIMPLE_OPERATION_COST;
          break;
        case JBC_goto:
          bcIndex += 2;
          calleeSize += SIMPLE_OPERATION_COST;
          break;
        case JBC_jsr:
          bcIndex += 2;
          calleeSize += JSR_COST;
          break;
        case JBC_goto_w:
          bcIndex += 4;
          calleeSize += SIMPLE_OPERATION_COST;
          break;
        case JBC_jsr_w:
          bcIndex += 4;
          calleeSize += JSR_COST;
          break;
          // Hopefully just a move that will get reg alloced away, so cost 0
        case JBC_ret:
          bcIndex += 1;
          break;
        case JBC_ireturn:case JBC_lreturn:case JBC_freturn:
        case JBC_dreturn:case JBC_areturn:case JBC_return:
          break;
        case JBC_tableswitch:
          bcIndex = alignSwitch(bcIndex);
          bcIndex += 4;         // skip over default
          int low = getIntOffset(bcIndex, bytecodes);
          bcIndex += 4;
          int high = getIntOffset(bcIndex, bytecodes);
          bcIndex += 4;
          bcIndex += (high - low + 1)*4;        // skip over rest of tableswitch
          calleeSize += SWITCH_COST;
          break;
        case JBC_lookupswitch:
          bcIndex = alignSwitch(bcIndex);
          bcIndex += 4;         // skip over default 
          int numPairs = getIntOffset(bcIndex, bytecodes);
          bcIndex += 4 + (numPairs*8);          // skip rest of lookupswitch
          calleeSize += SWITCH_COST;
          break;
          // Load/store off of jtoc. expected cost is 1 instr
        case JBC_getstatic:case JBC_putstatic:
	  {
          int constantPoolIndex = (bytecodes[bcIndex++] & 0xFF) << 8;
          constantPoolIndex |= (bytecodes[bcIndex++] & 0xFF);
          int fieldId = klass.getFieldRefId(constantPoolIndex);
          VM_Field f = VM_FieldDictionary.getValue(fieldId);
          written.addUniqueElement(f);
          summary = setFieldOp(summary);
          calleeSize += SIMPLE_OPERATION_COST;
          break;
	  }
          // Load/store off of an object. expected cost is 1 instr
        case JBC_getfield:case JBC_putfield: 
	  {
          int constantPoolIndex = (bytecodes[bcIndex++] & 0xFF) << 8;
          constantPoolIndex |= (bytecodes[bcIndex++] & 0xFF);
          int fieldId = klass.getFieldRefId(constantPoolIndex);
          VM_Field f = VM_FieldDictionary.getValue(fieldId);
          written.addUniqueElement(f);
          summary = setFieldOp(summary);
          calleeSize += SIMPLE_OPERATION_COST;
          break;
	  }
          // Various flavors of calls. Assign them call cost (differentiate?)
        case JBC_invokevirtual:case JBC_invokespecial:
        case JBC_invokestatic:   // Special case VM_Magic's as being cheaper.
          int constantPoolIndex = (bytecodes[bcIndex++] & 0xFF) << 8;
          constantPoolIndex |= (bytecodes[bcIndex++] & 0xFF);
          VM_Method meth = method.getDeclaringClass().getMethodRef(
              constantPoolIndex);
          if (meth.getDeclaringClass().isMagicType() ||
	      meth.getDeclaringClass().isWordType()) {
            summary = setMagic(summary);
            summary = setInvoke(summary);
            calleeSize += MAGIC_COST;
          } else {
            summary = setInvoke(summary);
            calleeSize += CALL_COST;
          }
          break;
        case JBC_invokeinterface:
          bcIndex += 4;
          summary = setInvoke(summary);
          calleeSize += CALL_COST;
          break;
        case JBC_xxxunusedxxx:
          if (VM.VerifyAssertions)
            VM._assert(VM.NOT_REACHED);
          break;
        case JBC_new:
          bcIndex += 2;
          summary = setAllocation(summary);
          calleeSize += ALLOCATION_COST;
          break;
        case JBC_newarray:
          bcIndex += 1;
          summary = setAllocation(summary);
          calleeSize += ALLOCATION_COST;
          break;
        case JBC_anewarray:
          bcIndex += 2;
          summary = setAllocation(summary);
          calleeSize += ALLOCATION_COST;
          break;
          // null check (maybe) and a load.
        case JBC_arraylength:
          calleeSize += SIMPLE_OPERATION_COST;
          break;
        case JBC_athrow:
          summary = setThrow(summary);
          calleeSize += THROW_COST;
          break;
        case JBC_checkcast:case JBC_instanceof:
          bcIndex += 2;
          calleeSize += CLASS_CHECK_COST;
          break;
        case JBC_monitorenter:case JBC_monitorexit:
          summary = setSynch(summary);
          calleeSize += SYNCH_COST;
          break;
        case JBC_wide:
          int w_code = (int)(bytecodes[bcIndex++] & 0xFF);
          if (w_code == JBC_iinc) {
            calleeSize += SIMPLE_OPERATION_COST;
            bcIndex += 4;
          } else {
            // the other wide ops should just be regAlloced away (0 cost)
            bcIndex += 2;
          }
          break;
        case JBC_multianewarray:
          summary = setAllocation(summary);
          calleeSize += CALL_COST;
          bcIndex += 3;
          break;
        default:
          if (VM.VerifyAssertions)
            VM._assert(VM.NOT_REACHED);
          break;
      }
    }
    summary = setSize(summary, calleeSize);

    if (DEBUG) {
      VM.sysWrite("Method summary for ");
      VM.sysWrite(method.toString());
      VM.sysWrite("\n\tActual bytecode length =");
      VM.sysWrite(bcLength, false);
      VM.sysWrite("\n\tInlined Size Estimate = ");
      VM.sysWrite(getSize(summary), false);
      if (hasMagic(summary)) {
        VM.sysWrite("\n\tContains magic");
      }
      if (hasSynch(summary)) {
        VM.sysWrite("\n\tContains monitorenter or monitorexit");
      }
      if (hasArrayOp(summary)) {
        VM.sysWrite("\n\tContains at least one array operation");
      }
      if (hasAllocation(summary)) {
        VM.sysWrite("\n\tContains at least one allocation operation");
      }
      if (hasThrow(summary)) {
        VM.sysWrite("\n\tContains at least one throw");
      }
      if (hasInvoke(summary)) {
        VM.sysWrite("\n\tContains at least one invoke");
      }
      if (hasFieldOp(summary)) {
        VM.sysWrite(
          "\n\tContains at least one getfield/putfield/getstatic/putstatic");
      }
      VM.sysWrite("\n");
    }

    // Having processed the method, inform VM_OptStaticProgramStats of the
    // new method and the number of bytecodes.
    VM_OptStaticProgramStats.newMethod(nBytecodes);

    // Store the summaries for the method
    storeSummary(method, summary);

    // Compress written before storing it.
    int numWritten = written.size();
    if (numWritten > 0) {
      VM_Field[] tmp = written.finish();
      storeWriteSummary(method, tmp);
      if (DEBUG) {
	VM.sysWrite("\tWrites the following fields:\n");
	for (int i=0; i<tmp.length; i++) {
	  VM.sysWrite("\t\t");
	  VM.sysWrite(tmp[i]);
	  VM.sysWrite("\n");
	}
      }
    } else {
      if (DEBUG) VM.sysWrite("\tWrites no fields.");
      storeWriteSummary(method, null);
    }

  }

  private static int alignSwitch(int bcIndex) {
    int align = bcIndex & 3;
    if (align != 0) bcIndex += 4 - align;                     // eat padding
    return bcIndex;
  }

  private static int getIntOffset(int index, byte[] bytecodes) {
    return (int)((((int)bytecodes[index]) << 24) 
		 | ((((int)bytecodes[ index + 1]) & 0xFF) << 16) 
		 | ((((int)bytecodes[index + 2]) & 0xFF) << 8) 
		 | (((int)bytecodes[ index + 3]) & 0xFF));
  }
}



