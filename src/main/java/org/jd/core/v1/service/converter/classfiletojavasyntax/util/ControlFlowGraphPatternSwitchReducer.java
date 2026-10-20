/*
 * Copyright (c) 2008-2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.util;

import org.jd.core.v1.model.classfile.ConstantPool;
import org.jd.core.v1.model.classfile.attribute.AttributeCode;
import org.jd.core.v1.model.classfile.constant.ConstantMemberRef;
import org.jd.core.v1.model.classfile.constant.ConstantNameAndType;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.cfg.BasicBlock;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.cfg.ControlFlowGraph;

import java.util.ArrayList;

import static org.jd.core.v1.service.converter.classfiletojavasyntax.model.cfg.BasicBlock.*;

/**
 * Java 21+ pattern matching 'switch' with guards ('case String s when s.isEmpty()') or nested patterns:
 * when a guard or a nested pattern does not match, javac stores the index of the next case label in the
 * 'restart' variable and jumps back to 'SwitchBootstraps.typeSwitch(selector, restart)':
 * <pre>
 * head:  aload selector; iload restart; invokedynamic typeSwitch; tableswitch ...
 * fail:  iconst k+1; istore restart; goto head
 * </pre>
 * There is no loop in the source. The 'fail' blocks become end blocks (like a 'throw'), so the loop reducer
 * does not create a loop, and the statement 'restart = k + 1;' stays as a marker for
 * {@link SwitchStatementMaker#makePatternSwitch}, which rebuilds the 'when' clauses.
 */
public class ControlFlowGraphPatternSwitchReducer {

    public static void reduce(ControlFlowGraph cfg) {
        byte[] code = cfg.getMethod().<AttributeCode>getAttribute("Code").getCode();
        ConstantPool constants = cfg.getMethod().getConstants();

        for (BasicBlock head : cfg.getBasicBlocks()) {
            if (head.getType() == TYPE_SWITCH_DECLARATION) {
                int restartIndex = getRestartIndex(constants, code, head.getFromOffset());

                if (restartIndex != -1) {
                    for (BasicBlock predecessor : new ArrayList<>(head.getPredecessors())) {
                        if ((predecessor.getNext() == head) && predecessor.matchType(TYPE_STATEMENTS|TYPE_GOTO) &&
                            isRestart(code, predecessor.getFromOffset(), predecessor.getToOffset(), restartIndex)) {
                            predecessor.setType(TYPE_THROW);
                            predecessor.setNext(END);
                            head.getPredecessors().remove(predecessor);

                            // 'if (!pattern) goto fail' (nested record pattern): the fail block becomes the next
                            // block, like for a guard ('if (guard) goto success'), so that the statements are
                            // 'if (!guard) restart = k + 1; ...' and the case keeps its value
                            for (BasicBlock conditional : predecessor.getPredecessors()) {
                                if ((conditional.getType() == TYPE_CONDITIONAL_BRANCH) && (conditional.getBranch() == predecessor) && (conditional.getNext() != predecessor)) {
                                    conditional.setBranch(conditional.getNext());
                                    conditional.setNext(predecessor);
                                    conditional.setInverseCondition(!conditional.mustInverseCondition());
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 'aload selector; iload restart; invokedynamic typeSwitch|enumSwitch; tableswitch|lookupswitch': returns 'restart' or -1 */
    protected static int getRestartIndex(ConstantPool constants, byte[] code, int offset) {
        int opcode = code[offset] & 255;

        if ((opcode >= 42) && (opcode <= 45)) { // ALOAD_0 ... ALOAD_3
            offset++;
        } else if (opcode == 25) { // ALOAD
            offset += 2;
        } else {
            return -1;
        }

        int restartIndex;

        opcode = code[offset] & 255;
        if ((opcode >= 26) && (opcode <= 29)) { // ILOAD_0 ... ILOAD_3
            restartIndex = opcode - 26;
            offset++;
        } else if (opcode == 21) { // ILOAD
            restartIndex = code[offset+1] & 255;
            offset += 2;
        } else {
            return -1;
        }

        if ((code[offset] & 255) != 186) { // INVOKEDYNAMIC
            return -1;
        }

        ConstantMemberRef indy = constants.getConstant(((code[offset+1] & 255) << 8) | (code[offset+2] & 255));
        ConstantNameAndType indyNameAndType = constants.getConstant(indy.getNameAndTypeIndex());
        String indyName = constants.getConstantUtf8(indyNameAndType.getNameIndex());

        if (!"typeSwitch".equals(indyName) && !"enumSwitch".equals(indyName)) {
            return -1;
        }

        opcode = code[offset+5] & 255;
        return ((opcode == 170) || (opcode == 171)) ? restartIndex : -1; // TABLESWITCH || LOOKUPSWITCH
    }

    /** 'iconst n; istore restart; goto head' */
    protected static boolean isRestart(byte[] code, int fromOffset, int toOffset, int restartIndex) {
        int offset = fromOffset;
        int opcode = code[offset] & 255;

        if ((opcode >= 3) && (opcode <= 8)) { // ICONST_0 ... ICONST_5
            offset++;
        } else if (opcode == 16) { // BIPUSH
            offset += 2;
        } else if (opcode == 17) { // SIPUSH
            offset += 3;
        } else {
            return false;
        }

        opcode = code[offset] & 255;
        if ((opcode >= 59) && (opcode <= 62)) { // ISTORE_0 ... ISTORE_3
            if (opcode - 59 != restartIndex) {
                return false;
            }
            offset++;
        } else if (opcode == 54) { // ISTORE
            if ((code[offset+1] & 255) != restartIndex) {
                return false;
            }
            offset += 2;
        } else {
            return false;
        }

        // The 'goto' may be in the block or in the next (deleted) block
        return (offset == toOffset) || (((code[offset] & 255) == 167) && (offset + 3 == toOffset)); // GOTO
    }
}
