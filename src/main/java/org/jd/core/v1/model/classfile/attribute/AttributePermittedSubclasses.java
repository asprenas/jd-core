/*
 * Copyright (c) 2008-2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package org.jd.core.v1.model.classfile.attribute;

/** Java 17+ 'PermittedSubclasses' attribute of a sealed class or interface. */
public class AttributePermittedSubclasses implements Attribute {
    protected String[] internalTypeNames;

    public AttributePermittedSubclasses(String[] internalTypeNames) {
        this.internalTypeNames = internalTypeNames;
    }

    public String[] getInternalTypeNames() {
        return internalTypeNames;
    }
}
