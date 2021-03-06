/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.compilers.opt.ir.operand;

import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.classloader.TypeReference;

/**
 * A TypeOperand represents a type.
 * Used in checkcast, instanceof, new, etc.
 * It will contain either a RVMType (if the type can be resolved
 * at compile time) or a TypeReference (if the type cannot be resolved
 * at compile time).
 *
 * @see Operand
 * @see RVMType
 * @see TypeReference
 */
public final class TypeOperand extends Operand {

  /**
   * A type
   */
  private final RVMType type;

  /**
   * The data type.
   */
  private final TypeReference typeRef;

  public TypeOperand(RVMType typ) {
    type = typ;
    typeRef = type.getTypeRef();
  }

  public TypeOperand(TypeReference tr) {
    type = tr.peekType();
    typeRef = tr;
  }

  private TypeOperand(RVMType t, TypeReference tr) {
    type = t;
    typeRef = tr;
  }

  @Override
  public TypeReference getType() {
    return TypeReference.Type;
  }

  /**
   * @return the TypeReference for this type operand
   */
  public TypeReference getTypeRef() {
    return typeRef;
  }

  /**
   * @return the RVMType for this type operand -- may be null
   */
  public RVMType getVMType() {
    if (type != null)
      return type;
    else
      return typeRef.peekType();
  }

  @Override
  public Operand copy() {
    return new TypeOperand(type, typeRef);
  }

  @Override
  public boolean similar(Operand op) {
    if (op instanceof TypeOperand) {
      TypeOperand that = (TypeOperand) op;
      return type == that.type && typeRef == that.typeRef;
    } else {
      return false;
    }
  }

  /**
   * Returns the string representation of this operand.
   *
   * @return a string representation of this operand.
   */
  @Override
  public String toString() {
    if (type != null) {
      return type.toString();
    } else {
      return typeRef.getName().toString();
    }
  }
}
