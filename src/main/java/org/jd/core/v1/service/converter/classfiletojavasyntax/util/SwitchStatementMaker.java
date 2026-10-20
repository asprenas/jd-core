/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.util;

import org.jd.core.v1.model.classfile.ClassFile;
import org.jd.core.v1.model.classfile.Constants;
import org.jd.core.v1.model.classfile.Field;
import org.jd.core.v1.model.javasyntax.expression.*;
import org.jd.core.v1.model.javasyntax.statement.*;
import org.jd.core.v1.model.javasyntax.type.ObjectType;
import org.jd.core.v1.model.javasyntax.type.PrimitiveType;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.ClassFileBodyDeclaration;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.ClassFileConstructorOrMethodDeclaration;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.ClassFileTypeDeclaration;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.expression.ClassFileLocalVariableReferenceExpression;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.expression.ClassFileSwitchIndexExpression;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.localvariable.AbstractLocalVariable;
import org.jd.core.v1.util.DefaultList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Iterator;
import java.util.Map;

public class SwitchStatementMaker {
    protected static final Integer MINUS_ONE = Integer.valueOf(-1);

    @SuppressWarnings("unchecked")
    public static void makeSwitchString(LocalVariableMaker localVariableMaker, Statements statements, SwitchStatement switchStatement) {
        int size = statements.size();
        SwitchStatement previousSwitchStatement = (SwitchStatement)statements.get(size - 2);

        if ((previousSwitchStatement.getCondition().getLineNumber() == switchStatement.getCondition().getLineNumber()) && previousSwitchStatement.getCondition().isMethodInvocationExpression()) {
            Expression expression = previousSwitchStatement.getCondition();

            if (expression.isMethodInvocationExpression()) {
                expression = expression.getExpression();

                if (expression.isLocalVariableReferenceExpression()) {
                    AbstractLocalVariable syntheticLV1 = ((ClassFileLocalVariableReferenceExpression)expression).getLocalVariable();
                    expression = statements.get(size - 4).getExpression().getLeftExpression();

                    if (expression.isLocalVariableReferenceExpression()) {
                        AbstractLocalVariable lv2 = ((ClassFileLocalVariableReferenceExpression)expression).getLocalVariable();

                        if (syntheticLV1.equals(lv2)) {
                            expression = statements.get(size - 3).getExpression();

                            if (expression.isBinaryOperatorExpression()) {
                                Expression boe2 = expression;

                                expression = boe2.getRightExpression();

                                if (expression.isIntegerConstantExpression() && MINUS_ONE.equals(expression.getIntegerValue())) {
                                    expression = switchStatement.getCondition();

                                    if (expression.isLocalVariableReferenceExpression()) {
                                        AbstractLocalVariable syntheticLV2 = ((ClassFileLocalVariableReferenceExpression)expression).getLocalVariable();

                                        if (syntheticLV2.equals(((ClassFileLocalVariableReferenceExpression)boe2.getLeftExpression()).getLocalVariable())) {
                                            MethodInvocationExpression mie = (MethodInvocationExpression) previousSwitchStatement.getCondition();

                                            if (mie.getName().equals("hashCode") && mie.getDescriptor().equals("()I")) {
                                                // Pattern found ==> Parse cases of the synthetic switch statement 'previousSwitchStatement'
                                                HashMap<Integer, String> map = new HashMap<>();

                                                // Create map<synthetic index -> string>
                                                for (SwitchStatement.Block block : previousSwitchStatement.getBlocks()) {
                                                    BaseStatement stmts = block.getStatements();

                                                    assert (stmts != null) && stmts.isStatements() && (stmts.size() > 0);

                                                    for (Statement stmt : stmts) {
                                                        if (!stmt.isIfStatement()) {
                                                            break;
                                                        }

                                                        expression = stmt.getCondition();

                                                        if (!expression.isMethodInvocationExpression()) {
                                                            break;
                                                        }

                                                        expression = expression.getParameters().getFirst();

                                                        if (!expression.isStringConstantExpression()) {
                                                            break;
                                                        }

                                                        String string = expression.getStringValue();

                                                        expression = stmt.getStatements().getFirst().getExpression().getRightExpression();

                                                        if (!expression.isIntegerConstantExpression()) {
                                                            break;
                                                        }

                                                        Integer index = expression.getIntegerValue();
                                                        map.put(index, string);
                                                    }
                                                }

                                                // Replace synthetic index by string
                                                for (SwitchStatement.Block block : switchStatement.getBlocks()) {
                                                    if (block.isSwitchStatementLabelBlock()) {
                                                        SwitchStatement.LabelBlock lb = (SwitchStatement.LabelBlock) block;

                                                        if (lb.getLabel() != SwitchStatement.DEFAULT_LABEL) {
                                                            SwitchStatement.ExpressionLabel el = (SwitchStatement.ExpressionLabel) lb.getLabel();
                                                            IntegerConstantExpression nce = (IntegerConstantExpression) el.getExpression();
                                                            el.setExpression(new StringConstantExpression(nce.getLineNumber(), map.get(nce.getIntegerValue())));
                                                        }
                                                    } else if (block.isSwitchStatementMultiLabelsBlock()) {
                                                        SwitchStatement.MultiLabelsBlock lmb = (SwitchStatement.MultiLabelsBlock) block;

                                                        for (SwitchStatement.Label label : lmb.getLabels()) {
                                                            if (label != SwitchStatement.DEFAULT_LABEL) {
                                                                SwitchStatement.ExpressionLabel el = (SwitchStatement.ExpressionLabel) label;
                                                                IntegerConstantExpression nce = (IntegerConstantExpression) el.getExpression();
                                                                el.setExpression(new StringConstantExpression(nce.getLineNumber(), map.get(nce.getIntegerValue())));
                                                            }
                                                        }
                                                    }
                                                }

                                                // Replace synthetic key
                                                expression = statements.get(size - 4).getExpression();

                                                if (expression.isBinaryOperatorExpression()) {
                                                    switchStatement.setCondition(expression.getRightExpression());

                                                    // Remove next synthetic statements
                                                    statements.subList(size - 4, size - 1).clear();

                                                    // Remove synthetic local variables
                                                    localVariableMaker.removeLocalVariable(syntheticLV1);
                                                    localVariableMaker.removeLocalVariable(syntheticLV2);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static void makeSwitchEnum(ClassFileBodyDeclaration bodyDeclaration, SwitchStatement switchStatement) {
        Expression expression = switchStatement.getCondition().getExpression();

        if (expression.isFieldReferenceExpression()) {
            FieldReferenceExpression fre = (FieldReferenceExpression)expression;

            if (fre.getDescriptor().equals("[I") && fre.getName().startsWith("$SwitchMap$")) {
                ClassFileTypeDeclaration syntheticClassDeclaration = bodyDeclaration.getInnerTypeDeclaration(fre.getInternalTypeName());

                if (syntheticClassDeclaration != null) {
                    // Javac switch-enum pattern
                    bodyDeclaration = (ClassFileBodyDeclaration) syntheticClassDeclaration.getBodyDeclaration();
                    DefaultList<Statement> statements = bodyDeclaration.getMethodDeclarations().get(0).getStatements().getList();
                    updateSwitchStatement(switchStatement, searchSwitchMap(fre, statements.iterator()));
                }
            }
        } else if (expression.isMethodInvocationExpression()) {
            MethodInvocationExpression mie = (MethodInvocationExpression)expression;
            String methodName = mie.getName();

            if (mie.getDescriptor().equals("()[I") && methodName.startsWith("$SWITCH_TABLE$")) {
                // Eclipse compiler switch-enum pattern
                for (ClassFileConstructorOrMethodDeclaration declaration : bodyDeclaration.getMethodDeclarations()) {
                     if (declaration.getMethod().getName().equals(methodName)) {
                         DefaultList<Statement> statements = declaration.getStatements().getList();
                         updateSwitchStatement(switchStatement, statements.listIterator(3));
                         break;
                     }
                }
            }
        }
    }

    protected static Iterator<Statement> searchSwitchMap(FieldReferenceExpression fre, Iterator<Statement> iterator) {
        String name = fre.getName();

        while (iterator.hasNext()) {
            Expression expression = iterator.next().getExpression().getLeftExpression();

            if (expression.isFieldReferenceExpression() && name.equals(expression.getName())) {
                return iterator;
            }
        }

        return iterator;
    }

    protected static void updateSwitchStatement(SwitchStatement switchStatement, Iterator<Statement> iterator) {
        // Create map<synthetic index -> enum name>
        HashMap<Integer, String> map = new HashMap<>();

        while (iterator.hasNext()) {
            Statement statement = iterator.next();

            if (!statement.isTryStatement()) {
                break;
            }

            BaseStatement statements = statement.getTryStatements();

            if (!statements.isList()) {
                break;
            }

            Expression expression = statements.getFirst().getExpression();

            if (!expression.isBinaryOperatorExpression()) {
                break;
            }

            Expression boe = expression;

            expression = boe.getRightExpression();

            if (!expression.isIntegerConstantExpression()) {
                break;
            }

            Integer index = expression.getIntegerValue();

            expression = boe.getLeftExpression();

            if (!expression.isArrayExpression()) {
                break;
            }

            expression = expression.getIndex();

            if (!expression.isMethodInvocationExpression()) {
                break;
            }

            expression = expression.getExpression();

            if (!expression.isFieldReferenceExpression()) {
                break;
            }

            String name = ((FieldReferenceExpression)expression).getName();

            map.put(index, name);
        }

        // Replace synthetic index by enum name
        Expression expression = switchStatement.getCondition().getIndex().getExpression();

        replaceLabels(switchStatement, (ObjectType)expression.getType(), map);

        // Replace synthetic key
        switchStatement.setCondition(expression);
    }

    /**
     * Javac 21+ switch on an enum of the same compilation unit: 'switch (e.ordinal())', no $SwitchMap.
     * The ordinal of a constant is its position among the enum fields of the enum class.
     */
    public static void makeSwitchEnumOrdinal(ClassFileBodyDeclaration bodyDeclaration, SwitchStatement switchStatement) {
        Expression condition = switchStatement.getCondition();

        if (!condition.isMethodInvocationExpression() || !"ordinal".equals(condition.getName()) || !"()I".equals(condition.getDescriptor())) {
            return;
        }

        Expression expression = condition.getExpression();

        if (!expression.getType().isObjectType()) {
            return;
        }

        ObjectType type = (ObjectType)expression.getType();
        ClassFileTypeDeclaration enumDeclaration = bodyDeclaration.getInnerTypeDeclaration(type.getInternalName());

        if (enumDeclaration == null) {
            return;
        }

        ClassFile enumClassFile = ((ClassFileBodyDeclaration)enumDeclaration.getBodyDeclaration()).getClassFile();

        if ((enumClassFile.getAccessFlags() & Constants.ACC_ENUM) == 0) {
            return;
        }

        HashMap<Integer, String> map = new HashMap<>();
        int ordinal = 0;

        for (Field field : enumClassFile.getFields()) {
            if ((field.getAccessFlags() & Constants.ACC_ENUM) != 0) {
                map.put(ordinal++, field.getName());
            }
        }

        if (replaceLabels(switchStatement, type, map)) {
            switchStatement.setCondition(expression);
        }
    }

    /** Replaces the integer labels by enum constants, unless an integer has no constant. */
    protected static boolean replaceLabels(SwitchStatement switchStatement, ObjectType type, Map<Integer, String> map) {
        for (SwitchStatement.Block block : switchStatement.getBlocks()) {
            if (block.isSwitchStatementLabelBlock()) {
                SwitchStatement.Label label = ((SwitchStatement.LabelBlock) block).getLabel();

                if ((label != SwitchStatement.DEFAULT_LABEL) && !map.containsKey(label.getExpression().getIntegerValue())) {
                    return false;
                }
            } else if (block.isSwitchStatementMultiLabelsBlock()) {
                for (SwitchStatement.Label label : ((SwitchStatement.MultiLabelsBlock) block).getLabels()) {
                    if ((label != SwitchStatement.DEFAULT_LABEL) && !map.containsKey(label.getExpression().getIntegerValue())) {
                        return false;
                    }
                }
            }
        }

        for (SwitchStatement.Block block : switchStatement.getBlocks()) {
            if (block.isSwitchStatementLabelBlock()) {
                SwitchStatement.LabelBlock lb = (SwitchStatement.LabelBlock) block;

                if (lb.getLabel() != SwitchStatement.DEFAULT_LABEL) {
                    SwitchStatement.ExpressionLabel el = (SwitchStatement.ExpressionLabel) lb.getLabel();
                    IntegerConstantExpression nce = (IntegerConstantExpression) el.getExpression();
                    el.setExpression(new EnumConstantReferenceExpression(nce.getLineNumber(), type, map.get(nce.getIntegerValue())));
                }
            } else if (block.isSwitchStatementMultiLabelsBlock()) {
                SwitchStatement.MultiLabelsBlock lmb = (SwitchStatement.MultiLabelsBlock) block;

                for (SwitchStatement.Label label : lmb.getLabels()) {
                    if (label != SwitchStatement.DEFAULT_LABEL) {
                        SwitchStatement.ExpressionLabel el = (SwitchStatement.ExpressionLabel) label;
                        IntegerConstantExpression nce = (IntegerConstantExpression) el.getExpression();
                        el.setExpression(new EnumConstantReferenceExpression(nce.getLineNumber(), type, map.get(nce.getIntegerValue())));
                    }
                }
            }
        }

        return true;
    }

    /**
     * Java 21+ pattern matching 'switch': the index computed for SwitchBootstraps.typeSwitch / enumSwitch is
     * replaced by the selector and the integer labels by the source labels: 'case Integer i:' (the cast
     * assignment starting the block becomes the binding), 'case null:', 'case "a":', 'case 1:',
     * 'case Coin.HEADS:', 'case String s when s.isEmpty():'. Returns false (nothing changed) when the switch
     * is not understood.
     * <p>
     * Guard: when it does not match, javac restarts the switch at the next label. The jump back is cut by
     * {@link ControlFlowGraphPatternSwitchReducer}, the block of label k then starts with
     * 'if (!guard) restart = k + 1;'. Without the 'when' clause this code is wrong: when the switch is not
     * understood, an IllegalStateException makes the caller fall back to byte code output.
     */
    public static boolean makePatternSwitch(Statements statements, SwitchStatement switchStatement, int majorVersion, boolean switchIsLastStatement) {
        Expression restart = ((ClassFileSwitchIndexExpression)switchStatement.getCondition()).getRestart();
        boolean restartAssigned = restart.isLocalVariableReferenceExpression() && assignsLocalVariable(switchStatement, restart);

        if (!makePatternSwitch(statements, switchStatement, majorVersion, switchIsLastStatement, restartAssigned)) {
            if (restartAssigned) {
                throw new IllegalStateException("makePatternSwitch : guard not understood");
            }
            return false;
        }
        if (restartAssigned && assignsLocalVariable(switchStatement, restart)) {
            throw new IllegalStateException("makePatternSwitch : unexpected 'restart' assignment");
        }

        return true;
    }

    protected static boolean makePatternSwitch(Statements statements, SwitchStatement switchStatement, int majorVersion, boolean switchIsLastStatement, boolean restartAssigned) {
        ClassFileSwitchIndexExpression index = (ClassFileSwitchIndexExpression)switchStatement.getCondition();
        Expression selector = index.getSelector();
        Expression restart = index.getRestart();
        List<ClassFileSwitchIndexExpression.Label> labels = index.getLabels();

        // Check every label can be written before changing anything
        HashSet<Integer> usedIndexes = new HashSet<>();

        for (SwitchStatement.Block block : switchStatement.getBlocks()) {
            List<SwitchStatement.Label> blockLabels = getLabels(block);

            for (SwitchStatement.Label label : blockLabels) {
                if (label != SwitchStatement.DEFAULT_LABEL) {
                    int i = label.getExpression().getIntegerValue();

                    if ((i < -1) || (i >= labels.size())) {
                        return false;
                    }
                    if ((i >= 0) && labels.get(i).isTypePattern() && ((blockLabels.size() != 1) || ((getBinding(block, selector) == null) && !startsWithRecordPattern(block, selector, restartAssigned))) && (majorVersion < 66)) {
                        // No binding variable and no '_' before Java 22
                        return false;
                    }
                    usedIndexes.add(i);
                }
            }
        }

        // Rewrite the labels
        for (SwitchStatement.Block block : switchStatement.getBlocks()) {
            List<SwitchStatement.Label> blockLabels = getLabels(block);
            int maxIndex = -1;

            for (SwitchStatement.Label label : blockLabels) {
                if (label != SwitchStatement.DEFAULT_LABEL) {
                    maxIndex = Math.max(maxIndex, label.getExpression().getIntegerValue());
                }
            }

            for (SwitchStatement.Label label : blockLabels) {
                if (label == SwitchStatement.DEFAULT_LABEL) {
                    continue;
                }

                SwitchStatement.ExpressionLabel expressionLabel = (SwitchStatement.ExpressionLabel)label;
                Expression value = expressionLabel.getExpression();
                int i = value.getIntegerValue();
                int lineNumber = value.getLineNumber();

                if (i == -1) {
                    expressionLabel.setExpression(new NullExpression(lineNumber, ObjectType.TYPE_OBJECT));
                } else {
                    ClassFileSwitchIndexExpression.Label l = labels.get(i);

                    if (l.isTypePattern()) {
                        Expression recordPattern = (blockLabels.size() == 1) ? removeRecordPattern(block, selector) : null;

                        if (recordPattern != null) {
                            // 'case R(...)': the pattern can not fail
                            expressionLabel.setExpression(recordPattern);
                            if (restartAssigned) {
                                expressionLabel.setGuard(removeGuard(block, restart, i + 1));
                            }
                        } else {
                            Expression binding = (blockLabels.size() == 1) ? removeBinding(block, selector) : null;
                            expressionLabel.setExpression(new TypePatternExpression(lineNumber, l.getType(), binding));
                            if (restartAssigned && (blockLabels.size() == 1)) {
                                Expression guard = removeGuard(block, restart, i + 1);

                                if ((binding == null) && (guard != null)) {
                                    // 'case R(...)' with nested patterns: 'selector instanceof R(...) && guard'
                                    Expression[] patternAndGuard = splitRecordPattern(guard, selector);

                                    if (patternAndGuard != null) {
                                        expressionLabel.setExpression(patternAndGuard[0]);
                                        guard = patternAndGuard[1];
                                    }
                                }
                                expressionLabel.setGuard(guard);
                            }
                        }
                    } else {
                        expressionLabel.setExpression(simplifyEnumConstant(l.getValue(), selector));
                    }
                }
            }

            if (restartAssigned && (blockLabels.size() > 1)) {
                // 'case Integer _, Long _': javac tests the types again, 'if (!(o instanceof Integer || o instanceof Long)) restart = n;'
                Expression test = removeGuard(block, restart, maxIndex + 1);

                if ((test != null) && !isTypeTestOfLabels(test, selector, blockLabels)) {
                    throw new IllegalStateException("makePatternSwitch : unexpected test of a multiple pattern label");
                }
            }
        }

        // A last type pattern sharing the 'default' block (javac uses the same target): 'case String t' replaces
        // 'default' when its binding starts the block
        for (int i = 0; i < labels.size(); i++) {
            if (!usedIndexes.contains(i) && labels.get(i).isTypePattern()) {
                List<SwitchStatement.Block> blocks = switchStatement.getBlocks();

                for (int j = 0; j < blocks.size(); j++) {
                    SwitchStatement.Block block = blocks.get(j);

                    if (block.isSwitchStatementLabelBlock() && (((SwitchStatement.LabelBlock)block).getLabel() == SwitchStatement.DEFAULT_LABEL)) {
                        Expression binding = removeBinding(block, selector);

                        if (binding != null) {
                            SwitchStatement.Label label = new SwitchStatement.ExpressionLabel(new TypePatternExpression(binding.getLineNumber(), labels.get(i).getType(), binding));
                            blocks.set(j, new SwitchStatement.LabelBlock(label, block.getStatements()));
                        }
                    }
                }
                break;
            }
        }

        boolean exhaustiveDefaultRemoved = removeExhaustiveDefaultBlock(switchStatement);
        switchStatement.setCondition(selector);

        // Remove 'Objects.requireNonNull(selector)' and 'restart = 0' written before the switch by javac
        int last = statements.size() - (switchIsLastStatement ? 2 : 1);
        boolean nullThrows = false;

        if (restart.isLocalVariableReferenceExpression()) {
            // No more used
            ((ClassFileLocalVariableReferenceExpression)restart).getLocalVariable().setDeclared(true);
        }

        for (int i = last; (i >= 0) && (i >= last - 2); i--) {
            Expression expression = statements.get(i).getExpression();

            if (isRequireNonNull(expression)) {
                nullThrows = true;
                statements.remove(i);
            } else if (restart.isLocalVariableReferenceExpression() && isAssignment(expression, restart)) {
                statements.remove(i);
            }
        }

        List<SwitchStatement.Block> blocks = switchStatement.getBlocks();
        int defaultIndex = -1;

        for (int i = 0; i < blocks.size(); i++) {
            if (getLabels(blocks.get(i)).contains(SwitchStatement.DEFAULT_LABEL)) {
                defaultIndex = i;
            }
        }

        if (!nullThrows && !usedIndexes.contains(-1)) {
            // No null check and no 'case null': javac sends null (index -1) to the 'default' target
            SwitchStatement.Label nullLabel = new SwitchStatement.ExpressionLabel(new NullExpression(selector.getLineNumber(), ObjectType.TYPE_OBJECT));

            if (defaultIndex != -1) {
                SwitchStatement.Block block = blocks.get(defaultIndex);
                DefaultList<SwitchStatement.Label> labelList = new DefaultList<>();
                labelList.add(nullLabel);
                labelList.addAll(getLabels(block));
                blocks.set(defaultIndex, new SwitchStatement.MultiLabelsBlock(labelList, block.getStatements()));
            } else if (switchIsLastStatement) {
                DefaultList<SwitchStatement.Label> labelList = new DefaultList<>();
                labelList.add(nullLabel);
                labelList.add(SwitchStatement.DEFAULT_LABEL);
                Statements breakStatements = new Statements();
                breakStatements.add(BreakStatement.BREAK);
                blocks.add(new SwitchStatement.MultiLabelsBlock(labelList, breakStatements));
            }
        } else if ((defaultIndex == -1) && switchIsLastStatement && !exhaustiveDefaultRemoved && !hasUnconditionalPattern(switchStatement, selector)) {
            // The 'default' target is the end of the switch: a pattern 'switch' statement must be exhaustive
            Statements breakStatements = new Statements();
            breakStatements.add(BreakStatement.BREAK);
            blocks.add(new SwitchStatement.LabelBlock(SwitchStatement.DEFAULT_LABEL, breakStatements));
        }

        return true;
    }

    /**
     * Exhaustive pattern switch with one case: javac jumps to the case body ending with 'return' and the 'default'
     * block throws, so the case body follows the switch. When the statements following the switch start with a
     * record pattern label ('selector instanceof R(...);'), they are moved to the empty block.
     */
    public static boolean moveFollowingStatements(Statements statements, int switchIndex, SwitchStatement switchStatement) {
        if (switchIndex >= statements.size()) {
            return false;
        }

        Expression selector = ((ClassFileSwitchIndexExpression)switchStatement.getCondition()).getSelector();

        if (!isRecordPatternStatement(statements.get(switchIndex), selector)) {
            return false;
        }

        List<SwitchStatement.Block> blocks = switchStatement.getBlocks();
        int emptyBlockIndex = -1;

        for (int i = 0; i < blocks.size(); i++) {
            BaseStatement blockStatements = blocks.get(i).getStatements();

            if ((blockStatements == null) || (blockStatements.size() == 0) || ((blockStatements.size() == 1) && blockStatements.getFirst().isBreakStatement())) {
                if (emptyBlockIndex != -1) {
                    return false;
                }
                emptyBlockIndex = i;
            }
        }

        if (emptyBlockIndex == -1) {
            return false;
        }

        List<Statement> following = statements.subList(switchIndex, statements.size());
        Statements blockStatements = new Statements(following);
        SwitchStatement.Block emptyBlock = blocks.get(emptyBlockIndex);

        following.clear();

        if (emptyBlock.isSwitchStatementLabelBlock()) {
            blocks.set(emptyBlockIndex, new SwitchStatement.LabelBlock(((SwitchStatement.LabelBlock)emptyBlock).getLabel(), blockStatements));
        } else {
            blocks.set(emptyBlockIndex, new SwitchStatement.MultiLabelsBlock(((SwitchStatement.MultiLabelsBlock)emptyBlock).getLabels(), blockStatements));
        }
        return true;
    }

    /** 'selector instanceof T1 || selector instanceof T2 ...' with the types of the labels, in the same order */
    protected static boolean isTypeTestOfLabels(Expression test, Expression selector, List<SwitchStatement.Label> labels) {
        ArrayList<Expression> tests = new ArrayList<>();

        while (test.isBinaryOperatorExpression() && "||".equals(test.getOperator())) {
            tests.add(0, test.getRightExpression());
            test = test.getLeftExpression();
        }
        tests.add(0, test);

        ArrayList<SwitchStatement.Label> patternLabels = new ArrayList<>();

        for (SwitchStatement.Label label : labels) {
            if (label != SwitchStatement.DEFAULT_LABEL) {
                patternLabels.add(label);
            }
        }

        if (tests.size() != patternLabels.size()) {
            return false;
        }

        for (int i = 0; i < tests.size(); i++) {
            Expression e = tests.get(i);
            Expression pattern = patternLabels.get(i).getExpression();

            if (!(e instanceof InstanceOfExpression) || !isSameLocalVariable(e.getExpression(), selector) || !pattern.isTypePatternExpression() ||
                !((InstanceOfExpression)e).getInstanceOfType().equals(pattern.getType())) {
                return false;
            }
        }

        return true;
    }

    protected static boolean startsWithRecordPattern(SwitchStatement.Block block, Expression selector, boolean restartAssigned) {
        BaseStatement statements = block.getStatements();

        if ((statements == null) || (statements.size() == 0)) {
            return false;
        }

        Statement first = statements.getFirst();
        // A nested pattern can fail: 'if (!(selector instanceof R(...))) restart = n;', checked by removeGuard
        return isRecordPatternStatement(first, selector) || (restartAssigned && first.isIfStatement());
    }

    /** First statement 'selector instanceof R(...);' of the block: removes it and returns the record pattern */
    protected static Expression removeRecordPattern(SwitchStatement.Block block, Expression selector) {
        BaseStatement statements = block.getStatements();

        if ((statements == null) || !statements.isList() || (statements.size() == 0) || !isRecordPatternStatement(statements.getFirst(), selector)) {
            return null;
        }

        InstanceOfExpression instanceOf = (InstanceOfExpression)statements.getFirst().getExpression();
        statements.getList().remove(0);
        return instanceOf.getPattern();
    }

    /**
     * 'selector instanceof R(...)' or 'selector instanceof R(...) && guard': returns {R(...), guard or null},
     * or null
     */
    protected static Expression[] splitRecordPattern(Expression expression, Expression selector) {
        if (isRecordPatternInstanceOf(expression, selector)) {
            return new Expression[] { ((InstanceOfExpression)expression).getPattern(), null };
        }

        if (expression.isBinaryOperatorExpression() && "&&".equals(expression.getOperator())) {
            Expression[] left = splitRecordPattern(expression.getLeftExpression(), selector);

            if (left != null) {
                Expression guard = (left[1] == null) ? expression.getRightExpression() :
                    new BinaryOperatorExpression(expression.getLineNumber(), expression.getType(), left[1], "&&", expression.getRightExpression(), 13);
                return new Expression[] { left[0], guard };
            }
        }

        return null;
    }

    protected static boolean isRecordPatternInstanceOf(Expression expression, Expression selector) {
        return (expression instanceof InstanceOfExpression) && (((InstanceOfExpression)expression).getPattern() != null) &&
               ((selector == null) || isSameLocalVariable(expression.getExpression(), selector));
    }

    protected static boolean isRecordPatternStatement(Statement statement, Expression selector) {
        if (!statement.isExpressionStatement()) {
            return false;
        }

        return isRecordPatternInstanceOf(statement.getExpression(), selector);
    }

    public static void checkNoAssignment(Statements statements, final int localVariableIndex) {
        statements.accept(new org.jd.core.v1.model.javasyntax.AbstractJavaSyntaxVisitor() {
            @Override
            public void visit(BinaryOperatorExpression expression) {
                Expression left = expression.getLeftExpression();

                if ("=".equals(expression.getOperator()) && left.isLocalVariableReferenceExpression() &&
                    (((ClassFileLocalVariableReferenceExpression)left).getLocalVariable().getIndex() == localVariableIndex)) {
                    throw new IllegalStateException("rewritten pattern switch not understood");
                }
                super.visit(expression);
            }
        });
    }

    public static void checkRecordPatternStatements(Statements statements) {
        statements.accept(new org.jd.core.v1.model.javasyntax.AbstractJavaSyntaxVisitor() {
            @Override
            public void visit(ExpressionStatement statement) {
                if (isRecordPatternStatement(statement, null)) {
                    throw new IllegalStateException("record pattern label not restored");
                }
                super.visit(statement);
            }
        });
    }

    /**
     * First statements 'if (condition) restart = nextIndex;' of the block: removes them and returns the guard
     * '!condition1 && !condition2...'
     */
    protected static Expression removeGuard(SwitchStatement.Block block, Expression restart, int nextIndex) {
        Expression guard = removeFirstGuard(block, restart, nextIndex);

        if (guard != null) {
            Expression next;

            while ((next = removeFirstGuard(block, restart, nextIndex)) != null) {
                guard = new BinaryOperatorExpression(guard.getLineNumber(), PrimitiveType.TYPE_BOOLEAN, guard, "&&", next, 13);
            }
        }

        return guard;
    }

    protected static Expression removeFirstGuard(SwitchStatement.Block block, Expression restart, int nextIndex) {
        BaseStatement statements = block.getStatements();

        if ((statements == null) || !statements.isList() || (statements.size() == 0) || !statements.getFirst().isIfStatement()) {
            return null;
        }

        IfStatement ifStatement = (IfStatement)statements.getFirst();
        BaseStatement thenStatements = ifStatement.getStatements();

        if ((thenStatements == null) || (thenStatements.size() != 1)) {
            return null;
        }

        Expression expression = thenStatements.getFirst().getExpression();

        if (!isAssignment(expression, restart) || !expression.getRightExpression().isIntegerConstantExpression() ||
            (expression.getRightExpression().getIntegerValue() != nextIndex)) {
            return null;
        }

        statements.getList().remove(0);
        return negate(ifStatement.getCondition());
    }

    protected static Expression negate(Expression expression) {
        int lineNumber = expression.getLineNumber();

        if (expression.isPreOperatorExpression() && "!".equals(expression.getOperator())) {
            return expression.getExpression();
        }

        if (expression.isBinaryOperatorExpression()) {
            Expression left = expression.getLeftExpression();
            Expression right = expression.getRightExpression();
            // '!(a < b)' is not 'a >= b' with NaN
            boolean floatingPoint = isFloatingPoint(left) || isFloatingPoint(right);

            switch (expression.getOperator()) {
                case "&&": return new BinaryOperatorExpression(lineNumber, PrimitiveType.TYPE_BOOLEAN, negate(left), "||", negate(right), 14);
                case "||": return new BinaryOperatorExpression(lineNumber, PrimitiveType.TYPE_BOOLEAN, negate(left), "&&", negate(right), 13);
                case "==": return new BinaryOperatorExpression(lineNumber, PrimitiveType.TYPE_BOOLEAN, left, "!=", right, 9);
                case "!=": return new BinaryOperatorExpression(lineNumber, PrimitiveType.TYPE_BOOLEAN, left, "==", right, 9);
                case "<":  if (!floatingPoint) return new BinaryOperatorExpression(lineNumber, PrimitiveType.TYPE_BOOLEAN, left, ">=", right, 8); break;
                case ">=": if (!floatingPoint) return new BinaryOperatorExpression(lineNumber, PrimitiveType.TYPE_BOOLEAN, left, "<", right, 8); break;
                case ">":  if (!floatingPoint) return new BinaryOperatorExpression(lineNumber, PrimitiveType.TYPE_BOOLEAN, left, "<=", right, 8); break;
                case "<=": if (!floatingPoint) return new BinaryOperatorExpression(lineNumber, PrimitiveType.TYPE_BOOLEAN, left, ">", right, 8); break;
            }
        }

        return new PreOperatorExpression(lineNumber, "!", expression);
    }

    protected static boolean isFloatingPoint(Expression expression) {
        return PrimitiveType.TYPE_FLOAT.equals(expression.getType()) || PrimitiveType.TYPE_DOUBLE.equals(expression.getType());
    }

    protected static boolean hasUnconditionalPattern(SwitchStatement switchStatement, Expression selector) {
        for (SwitchStatement.Block block : switchStatement.getBlocks()) {
            for (SwitchStatement.Label label : getLabels(block)) {
                if ((label != SwitchStatement.DEFAULT_LABEL) && label.getExpression().isTypePatternExpression() &&
                    label.getExpression().getType().equals(selector.getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    protected static List<SwitchStatement.Label> getLabels(SwitchStatement.Block block) {
        if (block.isSwitchStatementLabelBlock()) {
            return Collections.singletonList(((SwitchStatement.LabelBlock)block).getLabel());
        }
        return ((SwitchStatement.MultiLabelsBlock)block).getLabels();
    }

    /** First statement 'x = (T)selector;' or 'x = selector;' of the block: returns the variable x */
    protected static Expression getBinding(SwitchStatement.Block block, Expression selector) {
        BaseStatement statements = block.getStatements();

        if ((statements == null) || (statements.size() == 0)) {
            return null;
        }

        Expression expression = statements.getFirst().getExpression();

        if (!expression.isBinaryOperatorExpression() || !"=".equals(expression.getOperator()) || !expression.getLeftExpression().isLocalVariableReferenceExpression()) {
            return null;
        }

        Expression right = expression.getRightExpression();

        if (right.isCastExpression()) {
            right = right.getExpression();
        }

        return isSameLocalVariable(right, selector) ? expression.getLeftExpression() : null;
    }

    protected static Expression removeBinding(SwitchStatement.Block block, Expression selector) {
        Expression binding = getBinding(block, selector);

        if (binding != null) {
            BaseStatement statements = block.getStatements();

            if (statements.isList()) {
                statements.getList().remove(0);
            }
            // Declared by the pattern
            ((ClassFileLocalVariableReferenceExpression)binding).getLocalVariable().setDeclared(true);
        }

        return binding;
    }

    /** 'case Coin.HEADS' becomes 'case HEADS' when the selector is a Coin */
    protected static Expression simplifyEnumConstant(Expression value, Expression selector) {
        if (value.isFieldReferenceExpression() && value.getExpression().isObjectTypeReferenceExpression() && value.getType().equals(selector.getType())) {
            return new EnumConstantReferenceExpression(value.getLineNumber(), (ObjectType)value.getType(), value.getName());
        }
        return value;
    }

    protected static boolean isSameLocalVariable(Expression e1, Expression e2) {
        return e1.isLocalVariableReferenceExpression() && e2.isLocalVariableReferenceExpression() &&
               (((ClassFileLocalVariableReferenceExpression)e1).getLocalVariable().getIndex() == ((ClassFileLocalVariableReferenceExpression)e2).getLocalVariable().getIndex());
    }

    protected static boolean isRequireNonNull(Expression expression) {
        return expression.isMethodInvocationExpression() && "requireNonNull".equals(expression.getName()) && "java/util/Objects".equals(expression.getInternalTypeName());
    }

    protected static boolean isAssignment(Expression expression, Expression variable) {
        return expression.isBinaryOperatorExpression() && "=".equals(expression.getOperator()) && isSameLocalVariable(expression.getLeftExpression(), variable);
    }

    protected static boolean assignsLocalVariable(SwitchStatement switchStatement, final Expression variable) {
        final boolean[] found = { false };

        switchStatement.accept(new org.jd.core.v1.model.javasyntax.AbstractJavaSyntaxVisitor() {
            @Override
            public void visit(BinaryOperatorExpression expression) {
                if (isAssignment(expression, variable)) {
                    found[0] = true;
                }
                super.visit(expression);
            }
        });

        return found[0];
    }

    /**
     * javac adds a 'default' block throwing IncompatibleClassChangeError (JDK < 21) or MatchException (JDK 21+)
     * to an exhaustive switch expression on an enum or a sealed type. It is not part of the source.
     */
    public static boolean removeExhaustiveDefaultBlock(SwitchStatement switchStatement) {
        Iterator<SwitchStatement.Block> iterator = switchStatement.getBlocks().iterator();

        while (iterator.hasNext()) {
            SwitchStatement.Block block = iterator.next();

            if (block.isSwitchStatementLabelBlock() && (((SwitchStatement.LabelBlock)block).getLabel() == SwitchStatement.DEFAULT_LABEL)) {
                BaseStatement statements = block.getStatements();

                if (statements.isList() && (statements.size() == 1)) {
                    statements = statements.getFirst();
                }

                if (statements.isThrowStatement() && statements.getExpression().isNewExpression()) {
                    String internalName = ((NewExpression)statements.getExpression()).getObjectType().getInternalName();

                    if ("java/lang/IncompatibleClassChangeError".equals(internalName) || "java/lang/MatchException".equals(internalName)) {
                        iterator.remove();
                        return true;
                    }
                }
                return false;
            }
        }

        return false;
    }
}
