/*
 * This file is part of Jikes RVM (http://jikesrvm.sourceforge.net).
 * The Jikes RVM project is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright IBM Corp. 2001
 */
package org.jikesrvm.adaptive;

import org.jikesrvm.VM;
import org.jikesrvm.VM_Thread;
import org.jikesrvm.VM_Entrypoints;
import org.jikesrvm.VM_Synchronization;

import org.vmmagic.pragma.*;

/**
 * A VM_MethodListener defines a listener to collect method invocation samples.
 *
 * Samples are collected in a buffer.  
 * When sampleSize samples have been collected 
 * the listener's organizer is activated to process them.
 *  
 * Defines update's interface to be a compiled method identifier, CMID.
 * 
 * @author Matthew Arnold
 * @author Stephen Fink
 * @author Dave Grove
 * @author Michael Hind
 * @author Peter Sweeney
 */
@Uninterruptible final class VM_MethodListener extends VM_Listener {

  /**
   * Number of samples to be gathered before they are processed 
   */
  int sampleSize;  
  
  /**
   * Number of samples taken so far
   */
  int numSamples;
  
  /**
   * The sample buffer
   * Key Invariant: samples.length >= sampleSize
   */
  int[] samples;
  
  /**
   * @param sampleSize the initial sampleSize for the listener
   */
  public VM_MethodListener(int sampleSize) {
    this.sampleSize = sampleSize;
    samples = new int[sampleSize];
  }

  /** 
   * This method is called when a sample is taken.
   * It parameter "cmid" represents the compiled method ID of the method
   * which was executing at the time of the sample.  This method
   * bumps the counter and checks whether a threshold is reached.
   * <p>
   * NOTE: There can be multiple threads executing this method at the 
   *       same time. We attempt to ensure that the resulting race conditions
   *       are safely handled, but make no guarentee that every sample is
   *       actually recorded.
   *
   * @param cmid the compiled method ID to update
   * @param callerCmid a compiled method id for the caller, -1 if none
   * @param whereFrom Was this a yieldpoint in a PROLOGUE, BACKEDGE, or
   *         EPILOGUE?
   */
  public void update(int cmid, int callerCmid, int whereFrom) {
    if (VM.UseEpilogueYieldPoints) {
      // Use epilogue yieldpoints.  We increment one sample
      // for every yieldpoint.  On a prologue, we count the caller.
      // On backedges and epilogues, we count the current method.
      if (whereFrom == VM_Thread.PROLOGUE) {
        // Before getting a sample index, make sure we have something to insert
        if (callerCmid != -1) {
          recordSample(callerCmid);
        } // nothing to insert
      } else { 
        // loop backedge or epilogue.  
        recordSample(cmid);
      }
    } else {
      // Original scheme: No epilogue yieldpoints.  We increment two samples
      // for every yieldpoint.  On a prologue, we count both the caller
      // and callee.  On backedges, we count the current method twice.
      if (whereFrom == VM_Thread.PROLOGUE) {
        // Increment both for this method and the caller
        recordSample(cmid);
        if (callerCmid != -1) {
          recordSample(callerCmid);
        }
      } else { 
        // loop backedge.  We're only called once, so need to take
        // two samples to avoid penalizing methods with loops.
        recordSample(cmid);
        recordSample(cmid);
      }
    }
  }

  /**
   * This method records a sample containing the CMID (compiled method ID)
   * passed.  Since multiple threads may be taking samples concurrently,
   * we use fetchAndAdd to distribute indices into the buffer AND to record
   * when a sample is taken.  (Thread 1 may get an earlier index, but complete
   * the insertion after Thread 2.)
   *
   * @param CMID compiled method ID to record
   */
  private void recordSample(int CMID) {  
    // reserve the next available slot
    int idx = VM_Synchronization.fetchAndAdd(this, VM_Entrypoints.methodListenerNumSamplesField.getOffset(), 1);
    // make sure it is valid
    if (idx < sampleSize) {
      samples[idx] = CMID;
    }
    if (idx+1 == sampleSize) {
      // The last sample. 
      activateOrganizer(); 
    }
  }

  public void report() { }

  /**
   * Reset the buffer to prepare to take more samples.
   */
  public void reset() {
    numSamples = 0;
  }

  /**
   * @return the buffer of samples
   */
  public int[] getSamples() { return samples; }

  /**
   * @return how many samples in the array returned by getSamples are valid 
   */
  public int getNumSamples() {
    return (numSamples < sampleSize) ? numSamples : sampleSize;
  }
} 