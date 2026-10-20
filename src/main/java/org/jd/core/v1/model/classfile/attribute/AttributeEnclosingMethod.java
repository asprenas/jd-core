/*
 * Copyright (c) 2008-2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package org.jd.core.v1.model.classfile.attribute;

/** 'EnclosingMethod' attribute of a local or anonymous class. The method is null for an initializer. */
public class AttributeEnclosingMethod implements Attribute {
    protected String internalTypeName;
    protected String methodName;
    protected String methodDescriptor;

    public AttributeEnclosingMethod(String internalTypeName, String methodName, String methodDescriptor) {
        this.internalTypeName = internalTypeName;
        this.methodName = methodName;
        this.methodDescriptor = methodDescriptor;
    }

    public String getInternalTypeName() {
        return internalTypeName;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getMethodDescriptor() {
        return methodDescriptor;
    }
}
