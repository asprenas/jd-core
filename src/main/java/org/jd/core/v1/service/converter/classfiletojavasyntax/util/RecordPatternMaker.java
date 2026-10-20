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
import org.jd.core.v1.model.classfile.constant.ConstantMemberRef;
import org.jd.core.v1.model.classfile.constant.ConstantNameAndType;
import org.jd.core.v1.util.DefaultList;

import java.util.*;
import java.util.function.IntPredicate;

/**
 * Java 21+ record patterns: 'o instanceof Point(int x, int y)' and 'case Point(int x, int y)'.
 * <p>
 * javac deconstructs a record with straight-line code: type tests ('instanceof T; ifeq FAIL'), casts and
 * copies into synthetic variables, and accessor calls, each one wrapped in
 * 'try { } catch (Throwable t) { throw new MatchException(t.toString(), t); }'. This code has no source
 * equivalent and the decompiler can not rebuild it statement by statement.
 * <p>
 * {@link #make} recognizes each region and rewrites a copy of the byte code: when the pattern can fail, the
 * region becomes 'iconst_1; nop...; ifeq FAIL', otherwise 'nop...'. The MatchException handlers of the region
 * are removed. {@link ByteCodeParser} replaces the first instruction of the region by the pattern: the condition
 * 'o instanceof R(...)', or the statement 'selector instanceof R(...)' for a pattern switch label (see
 * {@link SwitchStatementMaker#makePatternSwitch}). The caller restores the original code (byte code fallback
 * output).
 */
public class RecordPatternMaker {
    /** A deconstructed value: the root (matched expression), a component, or a nested component */
    public static class Value {
        protected Value parent;
        protected String accessorName;
        protected String accessorDescriptor;
        protected String type;              // Internal name of the tested or cast type, or null
        protected int bindingIndex = -1;    // Local variable index of the binding, or -1
        protected int bindingOffset;        // First offset of the binding variable
        protected DefaultList<Value> components = new DefaultList<>();

        public Value getParent() { return parent; }
        public String getAccessorDescriptor() { return accessorDescriptor; }
        public String getType() { return type; }
        public int getBindingIndex() { return bindingIndex; }
        public int getBindingOffset() { return bindingOffset; }
        public DefaultList<Value> getComponents() { return components; }
        public boolean isRecord() { return !components.isEmpty(); }
    }

    public static class Region {
        protected HashSet<Integer> syntheticIndexes = new HashSet<>();
        protected int fromOffset;
        protected int toOffset;
        protected int failOffset = -1;      // -1: the pattern can not fail (switch label without nested test)
        protected int rootIndex;            // Local variable index of the matched expression
        protected boolean instanceOf;       // 'o instanceof R(...)' or the label of a pattern switch
        protected Value root = new Value();

        public int getFromOffset() { return fromOffset; }
        public int getToOffset() { return toOffset; }
        public int getFailOffset() { return failOffset; }
        public int getRootIndex() { return rootIndex; }
        public boolean isInstanceOf() { return instanceOf; }
        public Value getRoot() { return root; }
    }

    /**
     * @return the record patterns by start offset, or null. When not null, the code and the exception table of
     *         the method are replaced: the caller must restore them.
     */
    public static Map<Integer, Region> make(ClassFile classFile, Method method, Map<Integer, int[]> switchArguments) {
        AttributeCode attributeCode = method.getAttribute("Code");

        if ((attributeCode == null) || (attributeCode.getExceptionTable() == null)) {
            return null;
        }

        HashSet<Integer> accessorOffsets = new HashSet<>();

        for (CodeException codeException : attributeCode.getExceptionTable()) {
            if (isMatchExceptionHandler(method.getConstants(), attributeCode.getCode(), codeException)) {
                accessorOffsets.add(codeException.getStartPc());
            }
        }

        if (accessorOffsets.isEmpty()) {
            return null;
        }

        // Cases merged by javac in nested switches, then the other record patterns of the rewritten code
        Map<Integer, Region> flattened = RecordPatternSwitchMaker.flatten(classFile, method, accessorOffsets, switchArguments);
        Map<Integer, Region> regions = makeRegions(method, classFile.getMajorVersion());

        if (flattened == null) {
            return regions;
        }
        if (regions != null) {
            flattened.putAll(regions);
        }
        return flattened;
    }

    protected static Map<Integer, Region> makeRegions(Method method, int majorVersion) {
        AttributeCode attributeCode = method.getAttribute("Code");

        if ((attributeCode == null) || (attributeCode.getExceptionTable() == null)) {
            return null;
        }

        ConstantPool constants = method.getConstants();
        byte[] code = attributeCode.getCode();
        CodeException[] codeExceptions = attributeCode.getExceptionTable();
        HashSet<Integer> accessorOffsets = new HashSet<>();

        for (CodeException codeException : codeExceptions) {
            if (isMatchExceptionHandler(constants, code, codeException)) {
                accessorOffsets.add(codeException.getStartPc());
            }
        }

        if (accessorOffsets.isEmpty()) {
            return null;
        }

        AttributeLocalVariableTable localVariableTable = attributeCode.getAttribute("LocalVariableTable");
        LocalVariable[] localVariables = (localVariableTable == null) ? null : localVariableTable.getLocalVariableTable();
        HashSet<Integer> branchTargets = getBranchTargets(code, codeExceptions, accessorOffsets);
        TreeMap<Integer, Region> regions = new TreeMap<>();
        HashSet<Integer> usedAccessorOffsets = new HashSet<>();

        HashMap<Region, HashSet<Integer>> regionAccessorOffsetMap = new HashMap<>();

        for (int offset = 0; offset < code.length; ) {
            HashSet<Integer> regionAccessorOffsets = new HashSet<>();
            Region region = match(constants, code, offset, accessorOffsets, regionAccessorOffsets, localVariables, majorVersion);

            if ((region != null) && !containsBranchTarget(branchTargets, region)) {
                regions.put(offset, region);
                regionAccessorOffsetMap.put(region, regionAccessorOffsets);
                offset = region.toOffset;
            } else {
                offset += getInstructionLength(code, offset);
            }
        }

        // A synthetic variable of a region must not be read after it (a part of the pattern is not in the
        // region, e.g. javac merges the cases 'Point(int x, int y) when ...' and 'Point(int x, int y)' with a
        // nested switch on the components)
        regions.values().removeIf(region -> isSyntheticVariableRead(code, regions, region, localVariables));

        if (regions.isEmpty()) {
            return null;
        }

        for (Region region : regions.values()) {
            usedAccessorOffsets.addAll(regionAccessorOffsetMap.get(region));
        }

        // Rewrite a copy of the code
        byte[] newCode = code.clone();

        for (Region region : regions.values()) {
            Arrays.fill(newCode, region.fromOffset, region.toOffset, (byte)0); // NOP

            if (region.failOffset != -1) {
                newCode[region.fromOffset] = (byte)4; // ICONST_1, replaced by the condition
                int ifOffset = region.toOffset - 3;
                int delta = region.failOffset - ifOffset;
                newCode[ifOffset] = (byte)153; // IFEQ
                newCode[ifOffset+1] = (byte)(delta >> 8);
                newCode[ifOffset+2] = (byte)delta;
            }
        }

        // Remove the MatchException handlers of the accessor calls
        ArrayList<CodeException> newCodeExceptions = new ArrayList<>();

        for (CodeException codeException : codeExceptions) {
            if (!usedAccessorOffsets.contains(codeException.getStartPc())) {
                newCodeExceptions.add(codeException);
            }
        }

        attributeCode.setCode(newCode);
        attributeCode.setExceptionTable(newCodeExceptions.isEmpty() ? null : newCodeExceptions.toArray(new CodeException[0]));

        return regions;
    }

    protected static Region match(ConstantPool constants, byte[] code, int fromOffset, Set<Integer> accessorOffsets, Set<Integer> regionAccessorOffsets, LocalVariable[] localVariables, int majorVersion) {
        Region region = new Region();
        Value root = region.root;
        HashMap<Integer, Value> values = new HashMap<>(); // Synthetic variables
        int offset = fromOffset;
        int[] load = decodeLoad(code, offset);

        if ((load == null) || (load[0] != 'a')) {
            return null;
        }

        region.fromOffset = fromOffset;
        region.rootIndex = load[1];
        offset += load[2];

        int opcode = code[offset] & 255;

        if (opcode == 193) { // INSTANCEOF: 'o instanceof R(...)'
            if ((code[offset+3] & 255) != 153) { // IFEQ
                return null;
            }
            root.type = constants.getConstantTypeName(readUnsignedShort(code, offset+1));
            region.failOffset = offset + 3 + readSignedShort(code, offset+4);
            region.instanceOf = true;
            offset += 6;

            // 'aload o; checkcast R; astore $r' or 'aload o; astore $r'
            load = decodeLoad(code, offset);
            if ((load == null) || (load[0] != 'a') || (load[1] != region.rootIndex)) {
                return null;
            }
            offset += load[2];
            if ((code[offset] & 255) == 192) { // CHECKCAST
                offset += 3;
            }
        } else if (opcode == 192) { // CHECKCAST: pattern switch label, 'aload $selector; checkcast R; astore $r'
            root.type = constants.getConstantTypeName(readUnsignedShort(code, offset+1));
            offset += 3;
        } else if (decodeStore(code, offset) == null) {
            // Not a pattern switch label without cast: 'aload $selector; astore $r'
            return null;
        }

        int[] store = decodeStore(code, offset);
        if ((store == null) || (store[0] != 'a') || isNamed(localVariables, store[1], offset + store[2])) {
            return null;
        }
        values.put(store[1], root);
        region.syntheticIndexes.add(store[1]);
        offset += store[2];

        // Deconstruction
        int[] accessorCount = { 0 };

        offset = matchSteps(constants, code, offset, region, values, accessorOffsets, regionAccessorOffsets, localVariables,
                            failOffset -> checkFailOffset(region, failOffset), accessorCount);
        if (offset == -1) {
            return null;
        }

        region.toOffset = offset;

        if ((accessorCount[0] == 0) || !checkValue(root, majorVersion)) {
            return null;
        }
        if ((region.failOffset != -1) && (region.toOffset - region.fromOffset < 4)) {
            return null;
        }

        return region;
    }

    /**
     * Linear approximation: after the region, a synthetic variable of the region is read before being written
     * again (javac reuses the synthetic variables).
     */
    protected static boolean isSyntheticVariableRead(byte[] code, Map<Integer, Region> regions, Region region, LocalVariable[] localVariables) {
        HashSet<Integer> live = new HashSet<>(region.syntheticIndexes);

        for (int offset = region.toOffset; (offset < code.length) && !live.isEmpty(); ) {
            Region other = regions.get(offset);

            if (other != null) {
                // Written by the other region: its synthetic variables are not values of this region anymore
                live.removeAll(other.syntheticIndexes);
                offset = other.toOffset;
                continue;
            }

            int[] load = decodeLoad(code, offset);

            if ((load != null) && live.contains(load[1]) && !isNamed(localVariables, load[1], offset)) {
                return !isMatchExceptionHandlerLoad(code, offset);
            }

            int[] store = decodeStore(code, offset);

            if (store != null) {
                live.remove(store[1]);
            } else if (((code[offset] & 255) == 132) && live.contains(code[offset+1] & 255)) { // IINC
                return true;
            }

            offset += getInstructionLength(code, offset);
        }
        return false;
    }

    /** 'aload t' in 'astore t; new MatchException; dup; aload t; ...' */
    protected static boolean isMatchExceptionHandlerLoad(byte[] code, int offset) {
        return (offset >= 4) && ((code[offset-1] & 255) == 89) && ((code[offset-4] & 255) == 187); // DUP, NEW
    }

    /**
     * Straight-line deconstruction: type tests, casts and copies of the synthetic variables, accessor calls.
     * @return the offset of the first other instruction, or -1 when the code is not understood
     */
    protected static int matchSteps(ConstantPool constants, byte[] code, int offset, Region region, Map<Integer, Value> values, Set<Integer> accessorOffsets,
                                    Set<Integer> regionAccessorOffsets, LocalVariable[] localVariables, IntPredicate failCheck, int[] accessorCount) {
        int opcode;
        int[] load, store;

        while (offset < code.length) {
            opcode = code[offset] & 255;

            if ((opcode == 4) && ((code[offset+1] & 255) == 153)) { // ICONST_1; IFEQ: always true
                if (!failCheck.test(offset + 1 + readSignedShort(code, offset+2))) {
                    return -1;
                }
                offset += 4;
                continue;
            }

            load = decodeLoad(code, offset);
            if (load == null) {
                break;
            }

            Value value = values.get(load[1]);
            if (value == null) {
                break;
            }

            int next = offset + load[2];
            opcode = code[next] & 255;

            if ((load[0] == 'a') && (opcode == 193) && ((code[next+3] & 255) == 153)) { // INSTANCEOF; IFEQ: nested test
                if (!failCheck.test(next + 3 + readSignedShort(code, next+4))) {
                    return -1;
                }
                value.type = constants.getConstantTypeName(readUnsignedShort(code, next+1));
                offset = next + 6;
                continue;
            }

            if ((load[0] == 'a') && ((opcode == 182) || (opcode == 185)) && accessorOffsets.contains(next)) { // INVOKEVIRTUAL, INVOKEINTERFACE: accessor
                ConstantMemberRef accessor = constants.getConstant(readUnsignedShort(code, next+1));
                ConstantNameAndType accessorNameAndType = constants.getConstant(accessor.getNameAndTypeIndex());
                int storeOffset = next + ((opcode == 182) ? 3 : 5);

                String componentType = null;

                if (isBoxing(constants, code, storeOffset)) {
                    // javac 25: the primitive component of a nested switch is boxed
                    storeOffset += 3;
                } else if ((code[storeOffset] & 255) == 192) { // CHECKCAST: generic component
                    componentType = constants.getConstantTypeName(readUnsignedShort(code, storeOffset+1));
                    storeOffset += 3;
                }
                if (value.type == null) {
                    value.type = constants.getConstantTypeName(accessor.getClassIndex());
                }

                store = decodeStore(code, storeOffset);
                if ((store == null) || isNamed(localVariables, store[1], storeOffset + store[2])) {
                    return -1;
                }

                Value component = new Value();
                component.parent = value;
                component.accessorName = constants.getConstantUtf8(accessorNameAndType.getNameIndex());
                component.accessorDescriptor = constants.getConstantUtf8(accessorNameAndType.getDescriptorIndex());
                component.type = componentType;
                value.components.add(component);
                values.put(store[1], component);
                region.syntheticIndexes.add(store[1]);
                regionAccessorOffsets.add(next);
                accessorCount[0]++;
                offset = storeOffset + store[2];
                continue;
            }

            String castType = null;

            if ((load[0] == 'a') && (opcode == 192)) { // CHECKCAST
                castType = constants.getConstantTypeName(readUnsignedShort(code, next+1));
                next += 3;
            }

            boolean unboxing = isUnboxing(constants, code, next);

            if (unboxing) {
                // javac 25: 'aload $boxed; checkcast Integer; invokevirtual intValue; istore x'
                next += 3;
            }

            store = decodeStore(code, next);
            if ((store == null) || ((store[0] != load[0]) && !unboxing) || (unboxing && (store[0] == 'a'))) {
                break;
            }

            if ((castType != null) && !unboxing) {
                value.type = castType;
            }

            if (isNamed(localVariables, store[1], next + store[2])) {
                // Binding variable
                if ((value.bindingIndex != -1) || (value == region.root)) {
                    return -1;
                }
                value.bindingIndex = store[1];
                value.bindingOffset = next + store[2];
            } else {
                values.put(store[1], value);
                region.syntheticIndexes.add(store[1]);
            }

            offset = next + store[2];
        }


        return offset;
    }

    /** 'invokestatic java/lang/Integer.valueOf(I)Ljava/lang/Integer;' and the other wrapper types */
    protected static boolean isBoxing(ConstantPool constants, byte[] code, int offset) {
        if ((code[offset] & 255) != 184) { // INVOKESTATIC
            return false;
        }
        ConstantMemberRef cmr = constants.getConstant(readUnsignedShort(code, offset+1));
        ConstantNameAndType cnat = constants.getConstant(cmr.getNameAndTypeIndex());
        return "valueOf".equals(constants.getConstantUtf8(cnat.getNameIndex())) && isWrapperType(constants.getConstantTypeName(cmr.getClassIndex()));
    }

    /** 'invokevirtual java/lang/Integer.intValue()I' and the other wrapper types */
    protected static boolean isUnboxing(ConstantPool constants, byte[] code, int offset) {
        if ((code[offset] & 255) != 182) { // INVOKEVIRTUAL
            return false;
        }
        ConstantMemberRef cmr = constants.getConstant(readUnsignedShort(code, offset+1));
        ConstantNameAndType cnat = constants.getConstant(cmr.getNameAndTypeIndex());
        String name = constants.getConstantUtf8(cnat.getNameIndex());
        return name.endsWith("Value") && constants.getConstantUtf8(cnat.getDescriptorIndex()).startsWith("()") && isWrapperType(constants.getConstantTypeName(cmr.getClassIndex()));
    }

    protected static boolean isWrapperType(String internalTypeName) {
        switch (internalTypeName) {
            case "java/lang/Integer": case "java/lang/Long": case "java/lang/Float": case "java/lang/Double":
            case "java/lang/Short": case "java/lang/Byte": case "java/lang/Character": case "java/lang/Boolean":
                return true;
            default:
                return false;
        }
    }

    protected static boolean checkFailOffset(Region region, int failOffset) {
        if (region.failOffset == -1) {
            region.failOffset = failOffset;
            return true;
        }
        return region.failOffset == failOffset;
    }

    protected static boolean checkValue(Value value, int majorVersion) {
        if (value.isRecord()) {
            if ((value.type == null) || (value.bindingIndex != -1)) {
                return false;
            }
            for (Value component : value.components) {
                if (!checkValue(component, majorVersion)) {
                    return false;
                }
            }
            return true;
        }

        // Unnamed pattern '_' since Java 22
        return (value.bindingIndex != -1) || (majorVersion >= 66);
    }

    protected static boolean isNamed(LocalVariable[] localVariables, int index, int offset) {
        if (localVariables != null) {
            for (LocalVariable localVariable : localVariables) {
                if ((localVariable.getIndex() == index) && (localVariable.getStartPc() <= offset) && (offset < localVariable.getStartPc() + localVariable.getLength())) {
                    return true;
                }
            }
        }
        return false;
    }

    protected static boolean containsBranchTarget(Set<Integer> branchTargets, Region region) {
        for (int offset = region.fromOffset + 1; offset < region.toOffset; offset++) {
            if (branchTargets.contains(offset)) {
                return true;
            }
        }
        return false;
    }

    /** @return {type ('i', 'l', 'f', 'd' or 'a'), index, length} or null */
    protected static int[] decodeLoad(byte[] code, int offset) {
        int opcode = code[offset] & 255;

        if ((opcode >= 21) && (opcode <= 25)) { // ILOAD ... ALOAD
            return new int[] { "ilfda".charAt(opcode - 21), code[offset+1] & 255, 2 };
        } else if ((opcode >= 26) && (opcode <= 45)) { // ILOAD_0 ... ALOAD_3
            return new int[] { "ilfda".charAt((opcode - 26) / 4), (opcode - 26) % 4, 1 };
        }
        return null;
    }

    /** @return {type ('i', 'l', 'f', 'd' or 'a'), index, length} or null */
    protected static int[] decodeStore(byte[] code, int offset) {
        int opcode = code[offset] & 255;

        if ((opcode >= 54) && (opcode <= 58)) { // ISTORE ... ASTORE
            return new int[] { "ilfda".charAt(opcode - 54), code[offset+1] & 255, 2 };
        } else if ((opcode >= 59) && (opcode <= 78)) { // ISTORE_0 ... ASTORE_3
            return new int[] { "ilfda".charAt((opcode - 59) / 4), (opcode - 59) % 4, 1 };
        }
        return null;
    }

    protected static int readUnsignedShort(byte[] code, int offset) {
        return ((code[offset] & 255) << 8) | (code[offset+1] & 255);
    }

    protected static int readSignedShort(byte[] code, int offset) {
        return (short)readUnsignedShort(code, offset);
    }

    protected static int readInt(byte[] code, int offset) {
        return ((code[offset] & 255) << 24) | ((code[offset+1] & 255) << 16) | ((code[offset+2] & 255) << 8) | (code[offset+3] & 255);
    }

    protected static HashSet<Integer> getBranchTargets(byte[] code, CodeException[] codeExceptions, Set<Integer> accessorOffsets) {
        HashSet<Integer> targets = new HashSet<>();

        for (int offset = 0; offset < code.length; offset += getInstructionLength(code, offset)) {
            int opcode = code[offset] & 255;

            if (((opcode >= 153) && (opcode <= 168)) || (opcode == 198) || (opcode == 199)) { // IFEQ ... JSR, IFNULL, IFNONNULL
                targets.add(offset + readSignedShort(code, offset+1));
            } else if ((opcode == 200) || (opcode == 201)) { // GOTO_W, JSR_W
                targets.add(offset + readInt(code, offset+1));
            } else if ((opcode == 170) || (opcode == 171)) { // TABLESWITCH, LOOKUPSWITCH
                int i = (offset + 4) & 0xFFFC;
                targets.add(offset + readInt(code, i));

                if (opcode == 170) {
                    int low = readInt(code, i+4);
                    int high = readInt(code, i+8);
                    for (int j = 0; j <= high - low; j++) {
                        targets.add(offset + readInt(code, i + 12 + 4*j));
                    }
                } else {
                    int count = readInt(code, i+4);
                    for (int j = 0; j < count; j++) {
                        targets.add(offset + readInt(code, i + 12 + 8*j));
                    }
                }
            }
        }

        for (CodeException codeException : codeExceptions) {
            if (accessorOffsets.contains(codeException.getStartPc())) {
                continue;
            }
            targets.add(codeException.getStartPc());
            targets.add(codeException.getEndPc());
            targets.add(codeException.getHandlerPc());
        }

        return targets;
    }

    /** 'try { accessor() } catch (Throwable t) { throw new MatchException(t.toString(), t); }' */
    protected static boolean isMatchExceptionHandler(ConstantPool constants, byte[] code, CodeException codeException) {
        if ((codeException.getCatchType() == 0) || !"java/lang/Throwable".equals(constants.getConstantTypeName(codeException.getCatchType()))) {
            return false;
        }

        // The try block contains only one accessor call: 'invokevirtual' or 'invokeinterface' of a method without parameter
        int startPc = codeException.getStartPc();
        int opcode = code[startPc] & 255;

        if (!((opcode == 182) && (codeException.getEndPc() == startPc + 3)) && // INVOKEVIRTUAL
            !((opcode == 185) && (codeException.getEndPc() == startPc + 5))) { // INVOKEINTERFACE
            return false;
        }

        ConstantMemberRef accessor = constants.getConstant(readUnsignedShort(code, startPc+1));
        ConstantNameAndType accessorNameAndType = constants.getConstant(accessor.getNameAndTypeIndex());

        if (!constants.getConstantUtf8(accessorNameAndType.getDescriptorIndex()).startsWith("()")) {
            return false;
        }

        // Handler: astore t; new MatchException; dup; aload t; invokevirtual Throwable.toString(); aload t; invokespecial MatchException.<init>(String, Throwable); athrow
        int offset = codeException.getHandlerPc();
        int[] store = decodeStore(code, offset);

        if ((store == null) || (store[0] != 'a')) {
            return false;
        }
        offset += store[2];

        if (((code[offset] & 255) != 187) || // NEW
            !"java/lang/MatchException".equals(constants.getConstantTypeName(readUnsignedShort(code, offset+1))) ||
            ((code[offset+3] & 255) != 89)) { // DUP
            return false;
        }
        offset += 4;

        int[] load = decodeLoad(code, offset);
        if ((load == null) || (load[0] != 'a') || (load[1] != store[1]) || !isInvoke(constants, code, offset += load[2], 182, "java/lang/Throwable", "toString")) { // INVOKEVIRTUAL
            return false;
        }
        offset += 3;

        load = decodeLoad(code, offset);
        if ((load == null) || (load[0] != 'a') || (load[1] != store[1]) || !isInvoke(constants, code, offset += load[2], 183, "java/lang/MatchException", "<init>")) { // INVOKESPECIAL
            return false;
        }
        offset += 3;

        return (code[offset] & 255) == 191; // ATHROW
    }

    protected static boolean isInvoke(ConstantPool constants, byte[] code, int offset, int expectedOpcode, String typeName, String name) {
        if ((code[offset] & 255) != expectedOpcode) {
            return false;
        }

        ConstantMemberRef cmr = constants.getConstant(readUnsignedShort(code, offset+1));
        ConstantNameAndType cnat = constants.getConstant(cmr.getNameAndTypeIndex());

        return typeName.equals(constants.getConstantTypeName(cmr.getClassIndex())) && name.equals(constants.getConstantUtf8(cnat.getNameIndex()));
    }

    protected static int getInstructionLength(byte[] code, int offset) {
        int opcode = code[offset] & 255;

        switch (opcode) {
            case 16: case 18: // BIPUSH, LDC
            case 21: case 22: case 23: case 24: case 25: // ILOAD ... ALOAD
            case 54: case 55: case 56: case 57: case 58: // ISTORE ... ASTORE
            case 169: case 188: // RET, NEWARRAY
                return 2;
            case 17: case 19: case 20: // SIPUSH, LDC_W, LDC2_W
            case 132: // IINC
            case 153: case 154: case 155: case 156: case 157: case 158: case 159: case 160: // IFEQ ... IF_ICMPNE
            case 161: case 162: case 163: case 164: case 165: case 166: case 167: case 168: // IF_ICMPLT ... JSR
            case 178: case 179: case 180: case 181: case 182: case 183: case 184: // GETSTATIC ... INVOKESTATIC
            case 187: case 189: case 192: case 193: // NEW, ANEWARRAY, CHECKCAST, INSTANCEOF
            case 198: case 199: // IFNULL, IFNONNULL
                return 3;
            case 197: // MULTIANEWARRAY
                return 4;
            case 185: case 186: case 200: case 201: // INVOKEINTERFACE, INVOKEDYNAMIC, GOTO_W, JSR_W
                return 5;
            case 170: { // TABLESWITCH
                int i = (offset + 4) & 0xFFFC;
                int low = readInt(code, i+4);
                int high = readInt(code, i+8);
                return i - offset + 12 + 4 * (high - low + 1);
            }
            case 171: { // LOOKUPSWITCH
                int i = (offset + 4) & 0xFFFC;
                return i - offset + 8 + 8 * readInt(code, i+4);
            }
            case 196: // WIDE
                return ((code[offset+1] & 255) == 132) ? 6 : 4;
            default:
                return 1;
        }
    }
}
