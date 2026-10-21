/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.model.javasyntax.reference;

import java.util.Collection;
import java.util.HashMap;

/**
 * Type annotations of a declaration (ex: 'List<@NonNull String>'), indexed by target type, target index and type path.
 * Types are shared and compared during the conversion, so annotations are kept beside them and printed by position.
 */
public class TypeAnnotationReferences {
    // JVMS 'target_type' of declarations
    public static final int CLASS_TYPE_PARAMETER    = 0x00;
    public static final int METHOD_TYPE_PARAMETER   = 0x01;
    public static final int FIELD                   = 0x13;
    public static final int METHOD_RETURN           = 0x14;
    public static final int METHOD_FORMAL_PARAMETER = 0x16;
    public static final int THROWS                  = 0x17;
    // 'index' 65535 for 'extends', or the index of the interface
    public static final int SUPERTYPE               = 0x10;
    // 'index': (index of the type parameter) * 256 + index of the bound in the printed list
    public static final int CLASS_TYPE_PARAMETER_BOUND  = 0x11;
    public static final int METHOD_TYPE_PARAMETER_BOUND = 0x12;
    // JVMS 'target_type' of the method body
    public static final int LOCAL_VARIABLE          = 0x40;
    public static final int RESOURCE_VARIABLE       = 0x41;
    public static final int CATCH                   = 0x42;
    public static final int INSTANCEOF              = 0x43;
    public static final int NEW                     = 0x44;
    public static final int CONSTRUCTOR_REFERENCE   = 0x45;
    public static final int METHOD_REFERENCE        = 0x46;
    public static final int CAST                    = 0x47;

    public static final int EXTENDS_INDEX = 65535;

    protected HashMap<String, AnnotationReferences<AnnotationReference>> map = new HashMap<>();

    public void add(int targetType, int targetIndex, String typePath, AnnotationReference annotationReference) {
        map.computeIfAbsent(key(targetType, targetIndex, typePath), k -> new AnnotationReferences<>()).add(annotationReference);
    }

    /**
     * @return the annotations of the type at 'typePath', or null
     */
    public AnnotationReferences<AnnotationReference> get(int targetType, int targetIndex, String typePath) {
        return map.get(key(targetType, targetIndex, typePath));
    }

    public Collection<AnnotationReferences<AnnotationReference>> getAnnotationReferences() {
        return map.values();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    protected static String key(int targetType, int targetIndex, String typePath) {
        return targetType + ":" + targetIndex + ":" + typePath;
    }
}
