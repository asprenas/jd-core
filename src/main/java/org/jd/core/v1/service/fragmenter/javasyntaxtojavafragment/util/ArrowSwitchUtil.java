/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.util;

import org.jd.core.v1.model.javasyntax.AbstractJavaSyntaxVisitor;
import org.jd.core.v1.model.javasyntax.declaration.LocalVariableDeclarator;
import org.jd.core.v1.model.javasyntax.expression.Expression;
import org.jd.core.v1.model.javasyntax.expression.LocalVariableReferenceExpression;
import org.jd.core.v1.model.javasyntax.expression.RecordPatternExpression;
import org.jd.core.v1.model.javasyntax.expression.TypePatternExpression;
import org.jd.core.v1.model.javasyntax.statement.*;

import java.util.HashSet;
import java.util.List;

/**
 * Java 14+ 'case ... ->': a switch written with colons is written with arrows when it means the same thing.
 * <ul>
 *   <li>no fall-through: every block ends with 'break', 'yield', 'return', 'throw' or 'continue', except the last
 *       block of a switch statement</li>
 *   <li>the final 'break' is the only one that exits the switch: inside an arrow block, 'break' exits the switch
 *       too, but a 'break' in the middle of a block is kept unchanged rather than reinterpreted</li>
 *   <li>a variable declared in a block is not used by another block (a colon switch body is one scope)</li>
 *   <li>labels of a block can be merged: 'default' only with 'null', patterns only without bindings</li>
 * </ul>
 */
public class ArrowSwitchUtil {
    public static final int JAVA_14 = 58;

    public static boolean canUseArrows(SwitchStatement statement, boolean expression) {
        List<SwitchStatement.Block> blocks = statement.getBlocks();
        int size = blocks.size();

        if (size == 0) {
            return false;
        }

        HashSet<String> declaredNames = new HashSet<>();

        for (int i = 0; i < size; i++) {
            SwitchStatement.Block block = blocks.get(i);

            if (!canMergeLabels(block)) {
                return false;
            }

            BaseStatement statements = block.getStatements();
            int count = (statements == null) ? 0 : statements.size();
            boolean last = (i == size - 1);

            if (count == 0) {
                if (!last || expression) {
                    return false;
                }
                continue;
            }

            Statement lastStatement = getLast(statements);

            if (!isTerminal(lastStatement) && (!last || expression)) {
                return false;
            }

            // Other 'break' exiting the switch
            BreakVisitor breakVisitor = new BreakVisitor();
            statements.accept(breakVisitor);
            if (breakVisitor.count > (isUnlabeledBreak(lastStatement) ? 1 : 0)) {
                return false;
            }

            for (Statement s : statements) {
                if (s.isLocalVariableDeclarationStatement()) {
                    ((LocalVariableDeclarationStatement)s).getLocalVariableDeclarators().accept(new AbstractJavaSyntaxVisitor() {
                        @Override
                        public void visit(LocalVariableDeclarator declarator) {
                            declaredNames.add(declarator.getName());
                        }
                    });
                }
            }
        }

        if (!declaredNames.isEmpty() && (size > 1)) {
            // A variable declared in a block and used in another one: the switch body is one scope
            for (SwitchStatement.Block block : blocks) {
                if (block.getStatements() != null) {
                    HashSet<String> declaredHere = new HashSet<>();
                    HashSet<String> used = new HashSet<>();

                    block.getStatements().accept(new AbstractJavaSyntaxVisitor() {
                        @Override
                        public void visit(LocalVariableDeclarator declarator) {
                            declaredHere.add(declarator.getName());
                            super.visit(declarator);
                        }

                        @Override
                        public void visit(LocalVariableReferenceExpression expression) {
                            used.add(expression.getName());
                        }
                    });

                    used.removeAll(declaredHere);
                    used.retainAll(declaredNames);

                    if (!used.isEmpty()) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    /** @return the statements of the block without the final 'break' */
    public static BaseStatement getArrowStatements(SwitchStatement.Block block) {
        BaseStatement statements = block.getStatements();

        if ((statements == null) || (statements.size() == 0)) {
            return null;
        }

        if (!isUnlabeledBreak(getLast(statements))) {
            return statements;
        }

        if (statements.size() == 1) {
            return null;
        }

        Statements list = new Statements();

        for (Statement s : statements) {
            list.add(s);
        }

        list.remove(list.size() - 1);
        return (list.size() == 1) ? list.getFirst() : list;
    }

    protected static boolean canMergeLabels(SwitchStatement.Block block) {
        if (!(block instanceof SwitchStatement.MultiLabelsBlock)) {
            return true;
        }

        boolean hasDefault = false;
        boolean hasNull = false;
        boolean hasBindingPattern = false;
        int count = 0;

        for (SwitchStatement.Label label : ((SwitchStatement.MultiLabelsBlock)block).getLabels()) {
            count++;

            if (label == SwitchStatement.DEFAULT_LABEL) {
                hasDefault = true;
            } else {
                SwitchStatement.ExpressionLabel el = (SwitchStatement.ExpressionLabel)label;
                Expression e = el.getExpression();

                if (el.getGuard() != null) {
                    return false;
                }
                if (e.isNullExpression()) {
                    hasNull = true;
                } else if (e instanceof TypePatternExpression) {
                    hasBindingPattern |= (((TypePatternExpression)e).getVariable() != null);
                } else if (e instanceof RecordPatternExpression) {
                    // Its bindings can not be merged
                    return false;
                }
            }
        }

        if (hasDefault) {
            // 'case null, default'
            return hasNull && (count == 2);
        }

        return !hasBindingPattern;
    }

    protected static boolean isTerminal(Statement statement) {
        if (statement.isTryStatement() && (statement.getFinallyStatements() == null) && !statement.getCatchClauses().isEmpty()) {
            // 'try { yield a; } catch (E e) { yield b; }'
            if (!isTerminal(statement.getTryStatements())) {
                return false;
            }
            for (TryStatement.CatchClause catchClause : statement.getCatchClauses()) {
                if (!isTerminal(catchClause.getStatements())) {
                    return false;
                }
            }
            return true;
        }

        return statement.isBreakStatement() || statement.isYieldStatement() || statement.isReturnStatement() ||
               statement.isReturnExpressionStatement() || statement.isThrowStatement() || statement.isContinueStatement();
    }

    protected static boolean isTerminal(BaseStatement statements) {
        Statement last = ((statements == null) || (statements.size() == 0)) ? null : getLast(statements);
        return (last != null) && isTerminal(last);
    }

    protected static boolean isUnlabeledBreak(Statement statement) {
        return statement.isBreakStatement() && (((BreakStatement)statement).getLabel() == null);
    }

    protected static Statement getLast(BaseStatement statements) {
        Statement last = null;

        for (Statement s : statements) {
            last = s;
        }

        return last;
    }

    /** Counts the unlabeled 'break' exiting the switch ('BreakStatement.BREAK' is shared: no identity test) */
    protected static class BreakVisitor extends AbstractJavaSyntaxVisitor {
        protected int count;

        @Override
        public void visit(BreakStatement statement) {
            if (statement.getLabel() == null) {
                count++;
            }
        }

        // 'break' in these statements exits them, not the switch
        @Override public void visit(DoWhileStatement statement) {}
        @Override public void visit(ForEachStatement statement) {}
        @Override public void visit(ForStatement statement) {}
        @Override public void visit(WhileStatement statement) {}
        @Override public void visit(SwitchStatement statement) {}
    }
}
