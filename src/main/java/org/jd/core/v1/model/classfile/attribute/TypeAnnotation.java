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
    protected int secondIndex = -1;
    protected int[] localVariableRanges;
    protected String typePath;
    protected Annotation annotation;

    /**
     * @param targetType  'target_type'
     * @param targetIndex index of the type parameter, super type, formal parameter or throws type, byte code offset
     *                    of an instruction (0x43 to 0x4B), or -1
     * @param typePath    'type_path' as "kind,argument;" steps, "" for the type itself
     * @param annotation  annotation
     */
    public TypeAnnotation(int targetType, int targetIndex, String typePath, Annotation annotation) {
        this.targetType = targetType;
        this.targetIndex = targetIndex;
        this.typePath = typePath;
        this.annotation = annotation;
    }

    /** 'bound_index' (0x11, 0x12) or 'type_argument_index' (0x47 to 0x4B), or -1 */
    public int getSecondIndex() {
        return secondIndex;
    }

    public void setSecondIndex(int secondIndex) {
        this.secondIndex = secondIndex;
    }

    /** 'localvar_target' (0x40, 0x41): {start_pc, length, index} triples, or null */
    public int[] getLocalVariableRanges() {
        return localVariableRanges;
    }

    public void setLocalVariableRanges(int[] localVariableRanges) {
        this.localVariableRanges = localVariableRanges;
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
