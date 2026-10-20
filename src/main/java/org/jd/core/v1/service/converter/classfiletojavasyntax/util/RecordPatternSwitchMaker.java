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
import org.jd.core.v1.model.classfile.attribute.AttributeBootstrapMethods;
import org.jd.core.v1.model.classfile.attribute.AttributeCode;
import org.jd.core.v1.model.classfile.attribute.AttributeLocalVariableTable;
import org.jd.core.v1.model.classfile.attribute.CodeException;
import org.jd.core.v1.model.classfile.attribute.LocalVariable;
import org.jd.core.v1.model.classfile.constant.ConstantClass;
import org.jd.core.v1.model.classfile.constant.ConstantMemberRef;
import org.jd.core.v1.model.classfile.constant.ConstantNameAndType;

import java.io.ByteArrayOutputStream;
import java.util.*;

import static org.jd.core.v1.service.converter.classfiletojavasyntax.util.RecordPatternMaker.*;

/**
 * Java 21+ pattern switch: javac merges consecutive cases deconstructing the same record type,
 * <pre>
 * case Point(int x, int y) when x == y -> ...
 * case Point(int x, int y) -> ...
 * </pre>
 * into a tree of nested switches, one per component: 'typeSwitch(Integer.valueOf($x), $restart2)', then
 * 'typeSwitch(Integer.valueOf($y), $restart3)'. A failure restarts the nested switch at its next label, or the
 * enclosing switch when the nested switch has no more label.
 * <p>
 * {@link #flatten} rebuilds the original cases (the leaves of the tree, in order) in a copy of the code: a new
 * switch head with one label per case is appended to the code, every leaf gets an appended record pattern
 * region ({@link RecordPatternMaker.Region}) followed by a jump to its body, and every restart jump is
 * redirected to 'restart = index of the next case; goto new head'. The original tree becomes dead code.
 */
public class RecordPatternSwitchMaker {

    /** A switch on the selector (the outer one) or on a component */
    protected static class Head {
        protected int offset;                // 'aload/xload value'
        protected int endOffset;             // After the 'tableswitch' or 'lookupswitch'
        protected int indyOffset;
        protected int restartIndex;
        protected int[] labelArguments;      // Bootstrap arguments
        protected Map<Integer, Integer> targets = new HashMap<>(); // Label (-1 for null) -> target
        protected int defaultTarget;
        protected Head parent;               // null: the outer switch
        protected int parentLabel;
    }

    protected static class State {
        protected Region region;
        protected HashMap<Integer, Value> values;
        protected ArrayList<int[]> path = new ArrayList<>(); // {head index, label}
        protected boolean conditional;
    }

    protected static class Leaf {
        protected Region region;
        protected int outerLabel;
        protected ArrayList<int[]> path;
        protected int bodyOffset;
        protected boolean conditional;
        protected int newIndex;
    }

    protected static class Context {
        protected ConstantPool constants;
        protected byte[] code;
        protected AttributeBootstrapMethods bootstrapMethods;
        protected Set<Integer> accessorOffsets;
        protected HashSet<Integer> usedAccessorOffsets = new HashSet<>();
        protected LocalVariable[] localVariables;
        protected ArrayList<Head> heads = new ArrayList<>();
        protected ArrayList<Leaf> leaves = new ArrayList<>();
        protected ArrayList<int[]> consumedRanges = new ArrayList<>(); // Code of the tree replaced by the new code
        protected int majorVersion;
        protected boolean failed;
    }

    /**
     * @param switchArguments output: the bootstrap arguments of the new 'typeSwitch' calls, by offset
     * @return the record pattern regions of the appended code, or null. When not null, the code and the
     *         exception table of the method are replaced: the caller must restore them.
     */
    public static Map<Integer, Region> flatten(ClassFile classFile, Method method, Set<Integer> accessorOffsets, Map<Integer, int[]> switchArguments) {
        AttributeCode attributeCode = method.getAttribute("Code");
        AttributeLocalVariableTable localVariableTable = attributeCode.getAttribute("LocalVariableTable");
        AttributeBootstrapMethods bootstrapMethods = classFile.getAttribute("BootstrapMethods");

        if ((localVariableTable == null) || (bootstrapMethods == null)) {
            return null;
        }

        byte[] code = attributeCode.getCode();
        TreeMap<Integer, Region> regions = null;

        for (int offset = 0; offset < code.length; offset += getInstructionLength(code, offset)) {
            Context context = new Context();
            context.constants = method.getConstants();
            context.code = code;
            context.bootstrapMethods = bootstrapMethods;
            context.accessorOffsets = accessorOffsets;
            context.localVariables = localVariableTable.getLocalVariableTable();
            context.majorVersion = classFile.getMajorVersion();

            Head outer = matchHead(context, offset, -1, null);

            if ((outer == null) || (outer.labelArguments == null)) {
                continue;
            }

            context.heads.add(outer);

            // Walk the cases deconstructing a record with nested switches
            boolean merged = false;

            for (int label = 0; label < outer.labelArguments.length; label++) {
                Integer target = outer.targets.get(label);

                if (target == null) {
                    continue;
                }

                int leafCount = context.leaves.size();
                int headCount = context.heads.size();
                int rangeCount = context.consumedRanges.size();
                HashSet<Integer> usedAccessorOffsets = new HashSet<>(context.usedAccessorOffsets);

                if (walkCase(context, outer, label, target) && (context.heads.size() > headCount)) {
                    merged = true;
                } else {
                    // Not a tree: keep the case as it is
                    while (context.leaves.size() > leafCount) {
                        context.leaves.remove(context.leaves.size() - 1);
                    }
                    while (context.heads.size() > headCount) {
                        context.heads.remove(context.heads.size() - 1);
                    }
                    while (context.consumedRanges.size() > rangeCount) {
                        context.consumedRanges.remove(context.consumedRanges.size() - 1);
                    }
                    context.usedAccessorOffsets = usedAccessorOffsets;
                    context.failed = false;
                }
            }

            if (!merged) {
                continue;
            }

            Map<Integer, Region> leafRegions = rewrite(context, attributeCode, outer, switchArguments);

            if (leafRegions != null) {
                if (regions == null) {
                    regions = new TreeMap<>();
                }
                regions.putAll(leafRegions);
                code = attributeCode.getCode();
                // Only one merged switch per method is rewritten in this pass
                break;
            }
        }

        return regions;
    }

    /** 'xload value; [invokestatic box]; iload restart; invokedynamic typeSwitch; tableswitch|lookupswitch' */
    protected static Head matchHead(Context context, int offset, int valueIndex, Map<Integer, Value> values) {
        byte[] code = context.code;
        int[] load = decodeLoad(code, offset);

        if (load == null) {
            return null;
        }
        if ((valueIndex != -1) && (load[1] != valueIndex)) {
            return null;
        }

        Head head = new Head();
        head.offset = offset;

        int next = offset + load[2];

        if ((code[next] & 255) == 184) { // INVOKESTATIC: boxing of a primitive component
            if (load[0] == 'a') {
                return null;
            }
            next += 3;
        }

        int[] restart = decodeLoad(code, next);

        if ((restart == null) || (restart[0] != 'i')) {
            return null;
        }

        head.restartIndex = restart[1];
        next += restart[2];

        if ((code[next] & 255) != 186) { // INVOKEDYNAMIC
            return null;
        }

        ConstantMemberRef indy = context.constants.getConstant(readUnsignedShort(code, next+1));
        ConstantNameAndType indyNameAndType = context.constants.getConstant(indy.getNameAndTypeIndex());

        if (!"typeSwitch".equals(context.constants.getConstantUtf8(indyNameAndType.getNameIndex()))) {
            return null;
        }

        head.indyOffset = next;
        int[] arguments = context.bootstrapMethods.getBootstrapMethods()[indy.getClassIndex()].getBootstrapArguments();

        for (int argument : arguments) {
            if (!(context.constants.getConstant(argument) instanceof ConstantClass)) {
                // Only type labels
                return null;
            }
        }

        head.labelArguments = arguments;
        next += 5;

        int opcode = code[next] & 255;
        int i = (next + 4) & 0xFFFC;

        if (opcode == 170) { // TABLESWITCH
            head.defaultTarget = next + readInt(code, i);
            int low = readInt(code, i+4);
            int high = readInt(code, i+8);
            for (int j = 0; j <= high - low; j++) {
                head.targets.put(low + j, next + readInt(code, i + 12 + 4*j));
            }
            head.endOffset = i + 12 + 4 * (high - low + 1);
        } else if (opcode == 171) { // LOOKUPSWITCH
            head.defaultTarget = next + readInt(code, i);
            int count = readInt(code, i+4);
            for (int j = 0; j < count; j++) {
                head.targets.put(readInt(code, i + 8 + 8*j), next + readInt(code, i + 12 + 8*j));
            }
            head.endOffset = i + 8 + 8 * count;
        } else {
            return null;
        }

        // Labels without explicit target go to the default target
        for (Iterator<Map.Entry<Integer, Integer>> iterator = head.targets.entrySet().iterator(); iterator.hasNext(); ) {
            if (iterator.next().getValue() == head.defaultTarget) {
                iterator.remove();
            }
        }

        return head;
    }

    /** Case 'label' of the outer switch: 'aload $selector; [checkcast R]; astore $r; ...' */
    protected static boolean walkCase(Context context, Head outer, int label, int target) {
        byte[] code = context.code;
        int[] load = decodeLoad(code, target);
        int[] selector = decodeLoad(code, outer.offset);

        if ((load == null) || (load[0] != 'a') || (load[1] != selector[1])) {
            return false;
        }

        State state = new State();
        state.region = new Region();
        state.region.rootIndex = load[1];
        state.values = new HashMap<>();

        int offset = target + load[2];

        if ((code[offset] & 255) == 192) { // CHECKCAST
            state.region.root.type = context.constants.getConstantTypeName(readUnsignedShort(code, offset+1));
            offset += 3;
        }

        int[] store = decodeStore(code, offset);

        if ((store == null) || (store[0] != 'a') || isNamed(context.localVariables, store[1], offset + store[2])) {
            return false;
        }

        state.values.put(store[1], state.region.root);
        context.consumedRanges.add(new int[] { target, offset + store[2] });
        walk(context, state, offset + store[2], label);
        return !context.failed;
    }

    protected static void walk(Context context, State state, int offset, int outerLabel) {
        if (context.failed) {
            return;
        }

        int startOffset = offset;

        byte[] code = context.code;
        HashSet<Integer> accessors = new HashSet<>();
        int[] accessorCount = { 0 };

        offset = matchSteps(context.constants, code, offset, state.region, state.values, context.accessorOffsets, accessors, context.localVariables,
                            failOffset -> isRestart(context, failOffset) && (state.conditional = true), accessorCount);

        if (offset == -1) {
            context.failed = true;
            return;
        }

        context.usedAccessorOffsets.addAll(accessors);
        context.consumedRanges.add(new int[] { startOffset, offset });

        // Nested switch on a component: 'iconst_0; istore $restart; head: ...'
        if (((code[offset] & 255) == 3) && (decodeStore(code, offset+1) != null)) {
            int[] restartStore = decodeStore(code, offset+1);
            int headOffset = offset + 1 + restartStore[2];
            int[] load = decodeLoad(code, headOffset);
            Value component = (load == null) ? null : state.values.get(load[1]);

            if ((component != null) && (component.getParent() != null)) {
                Head head = matchHead(context, headOffset, load[1], state.values);

                if ((head == null) || (head.restartIndex != restartStore[1])) {
                    context.failed = true;
                    return;
                }

                int[] last = state.path.isEmpty() ? null : state.path.get(state.path.size() - 1);
                head.parent = (last == null) ? null : context.heads.get(last[0]);
                head.parentLabel = (last == null) ? outerLabel : last[1];
                context.heads.add(head);
                context.consumedRanges.add(new int[] { offset, head.endOffset });
                int headIndex = context.heads.size() - 1;

                for (int label = 0; label < head.labelArguments.length; label++) {
                    Integer target = head.targets.get(label);

                    if (target == null) {
                        continue;
                    }

                    State fork = copy(state);
                    Value value = fork.values.get(load[1]);
                    String descriptor = value.getAccessorDescriptor();

                    if (descriptor.charAt(descriptor.indexOf(')') + 1) == 'L') {
                        // Type test of a reference component
                        String type = context.constants.getConstantTypeName(head.labelArguments[label]);
                        String static_ = descriptor.substring(descriptor.indexOf(')') + 2, descriptor.length() - 1);
                        if (!type.equals(static_) || (value.type != null)) {
                            value.type = type;
                        }
                    }

                    fork.path.add(new int[] { headIndex, label });
                    walk(context, fork, target, outerLabel);
                }
                return;
            }
        }

        // Leaf: the body of a case
        if (!checkValue(state.region.root, context.majorVersion) || state.region.root.getComponents().isEmpty()) {
            context.failed = true;
            return;
        }

        Leaf leaf = new Leaf();
        leaf.region = state.region;
        leaf.outerLabel = outerLabel;
        leaf.path = state.path;
        leaf.bodyOffset = offset;
        leaf.conditional = state.conditional;
        context.leaves.add(leaf);
    }

    /** 'iconst n; istore $restart; goto head' for a head of the tree: returns {head index, n, length} or null */
    protected static int[] matchRestart(Context context, int offset) {
        byte[] code = context.code;
        int opcode = code[offset] & 255;
        int value, length;

        if ((opcode >= 2) && (opcode <= 8)) { // ICONST_M1 ... ICONST_5
            value = opcode - 3;
            length = 1;
        } else if (opcode == 16) { // BIPUSH
            value = code[offset+1];
            length = 2;
        } else if (opcode == 17) { // SIPUSH
            value = (short)readUnsignedShort(code, offset+1);
            length = 3;
        } else {
            return null;
        }

        int[] store = decodeStore(code, offset + length);

        if ((store == null) || (store[0] != 'i')) {
            return null;
        }

        length += store[2];

        int gotoOffset = offset + length;

        if ((code[gotoOffset] & 255) != 167) { // GOTO
            return null;
        }

        int target = gotoOffset + readSignedShort(code, gotoOffset+1);

        for (int i = 0; i < context.heads.size(); i++) {
            Head head = context.heads.get(i);

            if ((head.offset == target) && (head.restartIndex == store[1])) {
                return new int[] { i, value, length + 3 };
            }
        }

        return null;
    }

    protected static boolean isRestart(Context context, int offset) {
        return matchRestart(context, offset) != null;
    }

    protected static State copy(State state) {
        State copy = new State();
        IdentityHashMap<Value, Value> map = new IdentityHashMap<>();
        copy.region = new Region();
        copy.region.rootIndex = state.region.rootIndex;
        copy.region.root = copy(state.region.root, null, map);
        copy.values = new HashMap<>();
        for (Map.Entry<Integer, Value> entry : state.values.entrySet()) {
            copy.values.put(entry.getKey(), map.get(entry.getValue()));
        }
        copy.path = new ArrayList<>(state.path);
        copy.conditional = state.conditional;
        return copy;
    }

    protected static Value copy(Value value, Value parent, IdentityHashMap<Value, Value> map) {
        Value copy = new Value();
        copy.parent = parent;
        copy.accessorName = value.accessorName;
        copy.accessorDescriptor = value.accessorDescriptor;
        copy.type = value.type;
        copy.bindingIndex = value.bindingIndex;
        copy.bindingOffset = value.bindingOffset;
        map.put(value, copy);
        for (Value component : value.components) {
            copy.components.add(copy(component, copy, map));
        }
        return copy;
    }

    /** Index of the case continuing after a failure 'restart = value; goto head' */
    protected static int resolve(Context context, Head outer, Map<Integer, Integer> outerIndexes, int newCount, int headIndex, int value) {
        Head head = context.heads.get(headIndex);

        if (head == outer) {
            Integer index = outerIndexes.get(value);
            return (index == null) ? newCount : index;
        }

        for (Leaf leaf : context.leaves) {
            for (int[] step : leaf.path) {
                if ((step[0] == headIndex) && (step[1] >= value)) {
                    return leaf.newIndex;
                }
            }
        }

        // No more label in this nested switch: next label of the enclosing switch
        return resolve(context, outer, outerIndexes, newCount, (head.parent == null) ? 0 : context.heads.indexOf(head.parent), head.parentLabel + 1);
    }

    protected static Map<Integer, Region> rewrite(Context context, AttributeCode attributeCode, Head outer, Map<Integer, int[]> switchArguments) {
        byte[] code = context.code;
        int[] selector = decodeLoad(code, outer.offset);

        if ((selector[1] > 255) || (outer.restartIndex > 255)) {
            return null;
        }

        // New labels: the leaves replace the merged labels
        HashSet<Integer> mergedLabels = new HashSet<>();
        for (Leaf leaf : context.leaves) {
            mergedLabels.add(leaf.outerLabel);
        }

        ArrayList<Integer> newArguments = new ArrayList<>();
        HashMap<Integer, Integer> outerIndexes = new HashMap<>(); // Original label -> first new index
        HashMap<Integer, Integer> newTargets = new HashMap<>();   // New index -> original target (not merged)

        for (int label = 0; label < outer.labelArguments.length; label++) {
            outerIndexes.put(label, newArguments.size());

            if (mergedLabels.contains(label)) {
                for (Leaf leaf : context.leaves) {
                    if (leaf.outerLabel == label) {
                        leaf.newIndex = newArguments.size();
                        newArguments.add(outer.labelArguments[label]);
                    }
                }
            } else {
                Integer target = outer.targets.get(label);
                if (target != null) {
                    newTargets.put(newArguments.size(), target);
                }
                newArguments.add(outer.labelArguments[label]);
            }
        }

        int newCount = newArguments.size();
        byte[] newCode = code.clone();
        ByteArrayOutputStream appended = new ByteArrayOutputStream();
        int base = code.length;

        // Restart jumps -> 'sipush index; istore restart; goto newHead'
        ArrayList<int[]> restarts = new ArrayList<>(); // {offset, length, new index}

        for (int offset = 0; offset < code.length; offset += getInstructionLength(code, offset)) {
            int[] restart = matchRestart(context, offset);

            if (restart != null) {
                restarts.add(new int[] { offset, restart[2], resolve(context, outer, outerIndexes, newCount, restart[0], restart[1]) });
            }
        }

        // Layout: the new head replaces the old head and the dead code of the tree, before the case bodies (a
        // backward jump is a loop for the decompiler). The record pattern region of a leaf replaces the end of the
        // dead code just before its body, and falls through into it. The restart blocks are appended.
        int headOffset = outer.offset;
        int switchOffset = headOffset + 2 + 2 + 5;
        int padding = (4 - ((switchOffset + 1) % 4)) % 4;
        boolean hasNull = outer.targets.containsKey(-1);
        int low = hasNull ? -1 : 0;
        int high = newCount - 1;
        int headLength = 2 + 2 + 5 + 1 + padding + 12 + 4 * (high - low + 1);
        int restartLength = 3 + 2 + 3;
        int offset = headOffset + headLength;

        HashMap<Leaf, Integer> dispatchOffsets = new HashMap<>();
        HashMap<Leaf, Integer> failOffsets = new HashMap<>();

        for (Leaf leaf : context.leaves) {
            // Conditional: 'fail: restart = index + 1; goto head', then 'iconst_1; nop; ifeq fail' (an appended fail
            // block, after the case bodies, could be taken for the end of the switch)
            int failLength = leaf.conditional ? makeRestart(0, leaf.newIndex + 1, outer.restartIndex, headOffset).length : 0;
            int length = leaf.conditional ? (failLength + 5) : 1;
            boolean found = false;

            for (int[] range : context.consumedRanges) {
                if ((range[1] == leaf.bodyOffset) && (range[1] - range[0] >= length)) {
                    found = true;
                }
            }
            if (!found) {
                return null;
            }

            dispatchOffsets.put(leaf, leaf.bodyOffset - (leaf.conditional ? 5 : 1));
            if (leaf.conditional) {
                failOffsets.put(leaf, leaf.bodyOffset - length);
            }
        }

        int frontEnd = offset;
        boolean[] dead = new boolean[code.length];
        Arrays.fill(dead, outer.offset, outer.endOffset, true);
        for (int[] range : context.consumedRanges) {
            Arrays.fill(dead, range[0], range[1], true);
        }
        for (int i = headOffset; i < frontEnd; i++) {
            if ((i >= code.length) || !dead[i]) {
                return null;
            }
        }

        int restartBase = base;

        if (restartBase + restarts.size() * restartLength > 32767) {
            return null;
        }

        // Dead code of the tree: 'nop...; aconst_null; athrow', it must not fall through into a case body
        Arrays.fill(newCode, outer.offset, outer.endOffset, (byte)0); // NOP
        for (int[] range : context.consumedRanges) {
            if (range[1] - range[0] < 2) {
                return null;
            }
            Arrays.fill(newCode, range[0], range[1], (byte)0); // NOP
            newCode[range[1]-2] = (byte)1; // ACONST_NULL
            newCode[range[1]-1] = (byte)191; // ATHROW
        }

        // New head
        ByteArrayOutputStream front = new ByteArrayOutputStream();
        front.write(25); // ALOAD
        front.write(selector[1]);
        front.write(21); // ILOAD
        front.write(outer.restartIndex);
        front.write(code, outer.indyOffset, 5);
        front.write(170); // TABLESWITCH
        for (int i = 0; i < padding; i++) {
            front.write(0);
        }
        writeInt(front, outer.defaultTarget - switchOffset);
        writeInt(front, low);
        writeInt(front, high);
        if (hasNull) {
            writeInt(front, outer.targets.get(-1) - switchOffset);
        }
        for (int index = 0; index < newCount; index++) {
            Integer target = newTargets.get(index);

            if (target == null) {
                target = outer.defaultTarget;

                for (Leaf leaf : context.leaves) {
                    if (leaf.newIndex == index) {
                        target = dispatchOffsets.get(leaf);
                    }
                }
            }

            writeInt(front, target - switchOffset);
        }

        int[] arguments = new int[newCount];
        for (int i = 0; i < newCount; i++) {
            arguments[i] = newArguments.get(i);
        }
        switchArguments.put(headOffset + 4, arguments);
        // The restart variable of the rewritten switch: no assignment may remain (see StatementMaker.make)
        switchArguments.put(-1 - outer.restartIndex, new int[0]);

        byte[] frontCode = front.toByteArray();
        System.arraycopy(frontCode, 0, newCode, headOffset, frontCode.length);

        // Leaf dispatches: record pattern region falling through into the body
        TreeMap<Integer, Region> regions = new TreeMap<>();

        for (Leaf leaf : context.leaves) {
            int dispatch = dispatchOffsets.get(leaf);
            Region region = leaf.region;
            region.fromOffset = dispatch;
            region.toOffset = leaf.bodyOffset;

            int start = dispatch;

            if (leaf.conditional) {
                int failOffset = failOffsets.get(leaf);
                byte[] fail = makeRestart(failOffset, leaf.newIndex + 1, outer.restartIndex, headOffset);
                System.arraycopy(fail, 0, newCode, failOffset, fail.length);
                start = failOffset;
                newCode[dispatch] = (byte)4; // ICONST_1, replaced by the condition
                newCode[dispatch+1] = (byte)0; // NOP
                newCode[dispatch+2] = (byte)153; // IFEQ
                newCode[dispatch+3] = (byte)((failOffset - (dispatch + 2)) >> 8);
                newCode[dispatch+4] = (byte)(failOffset - (dispatch + 2));
                region.failOffset = failOffset;
            } else {
                newCode[dispatch] = (byte)0; // NOP, replaced by the statement 'selector instanceof R(...);'
                region.failOffset = -1;
            }

            if (isConsumed(context, start - 1)) {
                // The dead code before must not fall through
                newCode[start-1] = (byte)191; // ATHROW
            }

            regions.put(dispatch, region);
        }

        // Restart blocks and jumps to them
        for (int i = 0; i < restarts.size(); i++) {
            int[] restart = restarts.get(i);
            int blockOffset = restartBase + i * restartLength;

            writeRestart(appended, blockOffset, restart[2], outer.restartIndex, headOffset);

            byte[] inline = makeRestart(restart[0], restart[2], outer.restartIndex, headOffset);
            int offset2 = restart[0];
            int end2 = restart[0] + restart[1];

            if (inline.length <= restart[1]) {
                // 'iconst n; istore restart; goto head', then dead code
                System.arraycopy(inline, 0, newCode, offset2, inline.length);
                offset2 += inline.length;
            } else {
                // 'goto block', then dead code
                newCode[offset2] = (byte)167; // GOTO
                newCode[offset2+1] = (byte)((blockOffset - offset2) >> 8);
                newCode[offset2+2] = (byte)(blockOffset - offset2);
                offset2 += 3;
            }
            if (offset2 < end2) {
                Arrays.fill(newCode, offset2, end2, (byte)0); // NOP
                newCode[end2-1] = (byte)191; // ATHROW
            }
        }

        byte[] tail = appended.toByteArray();
        byte[] result = Arrays.copyOf(newCode, base + tail.length);
        System.arraycopy(tail, 0, result, base, tail.length);

        // Remove the MatchException handlers of the accessor calls of the tree
        ArrayList<CodeException> codeExceptions = new ArrayList<>();
        for (CodeException codeException : attributeCode.getExceptionTable()) {
            if (!context.usedAccessorOffsets.contains(codeException.getStartPc())) {
                codeExceptions.add(codeException);
            }
        }

        attributeCode.setCode(result);
        attributeCode.setExceptionTable(codeExceptions.isEmpty() ? null : codeExceptions.toArray(new CodeException[0]));

        return regions;
    }

    protected static boolean isConsumed(Context context, int offset) {
        for (int[] range : context.consumedRanges) {
            if ((range[0] <= offset) && (offset < range[1])) {
                return true;
            }
        }
        return false;
    }

    /** Short form of 'restart = index; goto head' at offset */
    protected static byte[] makeRestart(int offset, int index, int restartIndex, int headOffset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        if ((index >= -1) && (index <= 5)) {
            out.write(3 + index); // ICONST_M1 ... ICONST_5
        } else if ((index >= -128) && (index <= 127)) {
            out.write(16); // BIPUSH
            out.write(index);
        } else {
            out.write(17); // SIPUSH
            writeShort(out, index);
        }

        if (restartIndex <= 3) {
            out.write(59 + restartIndex); // ISTORE_0 ... ISTORE_3
        } else {
            out.write(54); // ISTORE
            out.write(restartIndex);
        }

        int gotoOffset = offset + out.size();
        out.write(167); // GOTO
        writeShort(out, headOffset - gotoOffset);
        return out.toByteArray();
    }

    protected static void writeRestart(ByteArrayOutputStream out, int offset, int index, int restartIndex, int headOffset) {
        out.write(17); // SIPUSH
        writeShort(out, index);
        out.write(54); // ISTORE
        out.write(restartIndex);
        out.write(167); // GOTO
        writeShort(out, headOffset - (offset + 5));
    }

    protected static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 255);
        out.write(value & 255);
    }

    protected static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 255);
        out.write((value >> 16) & 255);
        out.write((value >> 8) & 255);
        out.write(value & 255);
    }
}
