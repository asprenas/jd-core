/*
 * Copyright (c) 2008-2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.util;

import org.jd.core.v1.model.classfile.ClassFile;
import org.jd.core.v1.model.classfile.ConstantPool;
import org.jd.core.v1.model.classfile.Method;
import org.jd.core.v1.model.classfile.attribute.AttributeCode;
import org.jd.core.v1.model.classfile.attribute.AttributeLocalVariableTable;
import org.jd.core.v1.model.classfile.attribute.CodeException;
import org.jd.core.v1.model.classfile.attribute.LocalVariable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

import static org.jd.core.v1.service.converter.classfiletojavasyntax.util.RecordPatternMaker.*;

/**
 * Java 16+ type patterns: 'o instanceof String s'.
 * <p>
 * javac compiles the pattern to 'aload o; instanceof T; ifeq FAIL; aload o; checkcast T; astore s'. Rebuilt
 * statement by statement, the store splits the condition: 'o instanceof String s &amp;&amp; s.length() &gt; 3' or
 * 'o instanceof Integer i ? i : -1' can not be rebuilt. Like {@link RecordPatternMaker}, the region is rewritten
 * to 'iconst_1; nop...; ifeq FAIL' and {@link ByteCodeParser} replaces 'iconst_1' by the pattern.
 * <p>
 * The same byte code is produced by 'if (o instanceof String) { String s = (String)o; ...': the pattern is then
 * an equivalent source. The binding must be a new variable: its 'LocalVariableTable' entry starts after the
 * store, so it is not an assignment of a variable declared before.
 */
public class TypePatternMaker {
    protected static final int JAVA_16 = 60;

    /**
     * @param regions the record patterns of the method, or null
     * @return the record and type patterns by start offset, or null. The code of the method is replaced when
     *         type patterns are found: the caller must restore it.
     */
    public static Map<Integer, Region> make(ClassFile classFile, Method method, Map<Integer, Region> regions) {
        AttributeCode attributeCode = method.getAttribute("Code");

        if ((classFile.getMajorVersion() < JAVA_16) || (attributeCode == null)) {
            return regions;
        }

        AttributeLocalVariableTable localVariableTable = attributeCode.getAttribute("LocalVariableTable");

        if (localVariableTable == null) {
            return regions;
        }

        ConstantPool constants = method.getConstants();
        byte[] code = attributeCode.getCode();
        LocalVariable[] localVariables = localVariableTable.getLocalVariableTable();
        HashSet<Integer> branchTargets = getBranchTargets(code, (attributeCode.getExceptionTable() == null) ? new CodeException[0] : attributeCode.getExceptionTable(), Collections.emptySet());
        TreeMap<Integer, Region> typePatterns = new TreeMap<>();

        for (int offset = 0; offset < code.length; ) {
            Region region = ((regions != null) && regions.containsKey(offset)) ? null : match(constants, code, offset, localVariables);

            if ((region != null) && !containsBranchTarget(branchTargets, region)) {
                typePatterns.put(offset, region);
                offset = region.toOffset;
            } else if ((regions != null) && regions.containsKey(offset)) {
                offset = regions.get(offset).toOffset;
            } else {
                offset += getInstructionLength(code, offset);
            }
        }

        if (typePatterns.isEmpty()) {
            return regions;
        }

        // Rewrite a copy of the code: 'iconst_1; nop...; ifeq FAIL'
        byte[] newCode = code.clone();

        for (Region region : typePatterns.values()) {
            Arrays.fill(newCode, region.fromOffset, region.toOffset, (byte)0); // NOP
            newCode[region.fromOffset] = (byte)4; // ICONST_1, replaced by the condition
            int ifOffset = region.toOffset - 3;
            int delta = region.failOffset - ifOffset;
            newCode[ifOffset] = (byte)153; // IFEQ
            newCode[ifOffset+1] = (byte)(delta >> 8);
            newCode[ifOffset+2] = (byte)delta;
        }

        attributeCode.setCode(newCode);

        if (regions != null) {
            typePatterns.putAll(regions);
        }
        return typePatterns;
    }

    /** 'aload o; instanceof T; ifeq FAIL; aload o; checkcast T; astore s' */
    protected static Region match(ConstantPool constants, byte[] code, int fromOffset, LocalVariable[] localVariables) {
        int offset = fromOffset;
        int[] load = decodeLoad(code, offset);

        if ((load == null) || (load[0] != 'a')) {
            return null;
        }

        int rootIndex = load[1];
        offset += load[2];

        if ((offset + 6 >= code.length) || ((code[offset] & 255) != 193) || ((code[offset+3] & 255) != 153)) { // INSTANCEOF, IFEQ
            return null;
        }

        int typeIndex = readUnsignedShort(code, offset+1);
        int failOffset = offset + 3 + readSignedShort(code, offset+4);
        offset += 6;

        load = decodeLoad(code, offset);
        if ((load == null) || (load[0] != 'a') || (load[1] != rootIndex)) {
            return null;
        }
        offset += load[2];

        if (((code[offset] & 255) != 192) || (readUnsignedShort(code, offset+1) != typeIndex)) { // CHECKCAST
            return null;
        }
        offset += 3;

        int[] store = decodeStore(code, offset);
        if ((store == null) || (store[0] != 'a') || (store[1] == rootIndex)) {
            return null;
        }
        offset += store[2];

        if ((failOffset <= offset) || !startsAt(localVariables, store[1], offset)) {
            return null;
        }

        Region region = new Region();

        region.fromOffset = fromOffset;
        region.toOffset = offset;
        region.failOffset = failOffset;
        region.rootIndex = rootIndex;
        region.instanceOf = true;
        region.root.type = constants.getConstantTypeName(typeIndex);
        region.root.bindingIndex = store[1];
        region.root.bindingOffset = offset;

        return region;
    }

    /** The variable at 'index' is declared by the store just before 'offset' */
    protected static boolean startsAt(LocalVariable[] localVariables, int index, int offset) {
        for (LocalVariable localVariable : localVariables) {
            if ((localVariable.getIndex() == index) && (localVariable.getStartPc() == offset)) {
                return true;
            }
        }
        return false;
    }
}
