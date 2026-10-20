/*
 * Copyright (c) 2008-2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package org.jd.core.v1.model.classfile.constant;

/**
 * CONSTANT_Dynamic (Java 11+): same layout as CONSTANT_InvokeDynamic. getClassIndex() returns the index of the
 * bootstrap method, getNameAndTypeIndex() the name and the field descriptor of the constant.
 */
public class ConstantDynamic extends ConstantMemberRef {
    public ConstantDynamic(int bootstrapMethodAttrIndex, int nameAndTypeIndex) {
        super(bootstrapMethodAttrIndex, nameAndTypeIndex);
        this.tag = CONSTANT_Dynamic;
    }
}
