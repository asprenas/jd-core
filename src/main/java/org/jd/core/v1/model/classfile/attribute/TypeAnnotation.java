/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.model.classfile.attribute;

/**
 * 'type_annotation' structure of 'RuntimeVisibleTypeAnnotations' and 'RuntimeInvisibleTypeAnnotations' (JVMS 4.7.20).
 */
public class TypeAnnotation {
    protected int targetType;
    protected int targetIndex;
    protected String typePath;
    protected Annotation annotation;

    /**
     * @param targetType  'target_type'
     * @param targetIndex index of the type parameter, super type, formal parameter or throws type; -1 if none
     * @param typePath    'type_path' as "kind,argument;" steps, "" for the type itself
     * @param annotation  annotation
     */
    public TypeAnnotation(int targetType, int targetIndex, String typePath, Annotation annotation) {
        this.targetType = targetType;
        this.targetIndex = targetIndex;
        this.typePath = typePath;
        this.annotation = annotation;
    }

    public int getTargetType() {
        return targetType;
    }

    public int getTargetIndex() {
        return targetIndex;
    }

    public String getTypePath() {
        return typePath;
    }

    public Annotation getAnnotation() {
        return annotation;
    }
}
