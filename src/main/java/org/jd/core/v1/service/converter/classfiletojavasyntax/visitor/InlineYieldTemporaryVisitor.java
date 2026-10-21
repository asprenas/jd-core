/*
 * Copyright (c) 2008-2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.visitor;

import org.jd.core.v1.model.javasyntax.AbstractJavaSyntaxVisitor;
import org.jd.core.v1.model.javasyntax.declaration.BaseLocalVariableDeclarator;
import org.jd.core.v1.model.javasyntax.expression.Expression;
import org.jd.core.v1.model.javasyntax.statement.BaseStatement;
import org.jd.core.v1.model.javasyntax.statement.LocalVariableDeclarationStatement;
import org.jd.core.v1.model.javasyntax.statement.Statement;
import org.jd.core.v1.model.javasyntax.statement.Statements;
import org.jd.core.v1.model.javasyntax.statement.SwitchStatement;
import org.jd.core.v1.model.javasyntax.statement.TryStatement;
import org.jd.core.v1.model.javasyntax.statement.YieldStatement;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.ClassFileLocalVariableDeclarator;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.localvariable.AbstractLocalVariable;

import java.util.ArrayList;
import java.util.List;

import static org.jd.core.v1.service.converter.classfiletojavasyntax.visitor.MoveDeclarationIntoSwitchBlockVisitor.countReferences;
import static org.jd.core.v1.service.converter.classfiletojavasyntax.visitor.MoveDeclarationIntoSwitchBlockVisitor.isReference;

/**
 * javac stores the value of a 'yield' in a temporary variable when the 'yield' is in a 'try' block:
 * <pre>
 * int i;
 * return switch (n) {
 *     case 0 -> { i = 0; yield i; }
 *     default -> { try { i = parse(s); } catch (E e) { i = -1; } yield i; }
 * };
 * </pre>
 * When the variable is not in the 'LocalVariableTable' and all its uses have these forms, the values are yielded:
 * 'case 0 -> 0;', 'default -> { try { yield parse(s); } catch (E e) { yield -1; } }'.
 */
public class InlineYieldTemporaryVisitor extends AbstractJavaSyntaxVisitor {
    @Override
    @SuppressWarnings("unchecked")
    public void visit(Statements list) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).isLocalVariableDeclarationStatement() && inlineYieldTemporary(list, i)) {
                list.remove(i--);
            }
        }

        super.visit(list);
    }

    @SuppressWarnings("unchecked")
    protected static boolean inlineYieldTemporary(Statements list, int index) {
        LocalVariableDeclarationStatement declaration = (LocalVariableDeclarationStatement)list.get(index);
        BaseLocalVariableDeclarator declarators = declaration.getLocalVariableDeclarators();

        if (!(declarators instanceof ClassFileLocalVariableDeclarator) || (((ClassFileLocalVariableDeclarator)declarators).getVariableInitializer() != null)) {
            return false;
        }

        AbstractLocalVariable localVariable = ((ClassFileLocalVariableDeclarator)declarators).getLocalVariable();

        if (!localVariable.isGeneratedName()) {
            return false;
        }

        List<Statement> following = list.subList(index + 1, list.size());
        int count = countReferences(following, localVariable);

        if (count == 0) {
            return false;
        }

        // The blocks of the first switch using the variable
        SearchSwitchVisitor searchSwitchVisitor = new SearchSwitchVisitor(localVariable);

        for (Statement statement : following) {
            statement.accept(searchSwitchVisitor);
            if (searchSwitchVisitor.switchStatement != null) {
                break;
            }
        }

        if (searchSwitchVisitor.switchStatement == null) {
            return false;
        }

        // Check all the uses before changing a block
        List<Statements> blocks = new ArrayList<>();
        int consumed = 0;

        for (SwitchStatement.Block block : searchSwitchVisitor.switchStatement.getBlocks()) {
            BaseStatement statements = block.getStatements();

            if ((statements == null) || !statements.isList() || (countReferences(statements.getList(), localVariable) == 0)) {
                continue;
            }

            int uses = countYieldUses((Statements)statements, localVariable);

            if (uses == -1) {
                return false;
            }
            blocks.add((Statements)statements);
            consumed += uses;
        }

        if (consumed != count) {
            return false;
        }

        for (Statements statements : blocks) {
            yieldValues(statements);
        }

        return true;
    }

    /** @return the number of references of 'yield v' and the assignments before it, -1 for another form */
    protected static int countYieldUses(Statements statements, AbstractLocalVariable localVariable) {
        int size = statements.size();

        if ((size < 2) || !isYieldOf(statements.get(size - 1), localVariable)) {
            return -1;
        }

        Statement previous = statements.get(size - 2);

        if (isAssignmentOf(previous, localVariable)) {
            // 'v = E; yield v;'
            return 2;
        }

        if (previous.isTryStatement() && (((TryStatement)previous).getResources() == null) && (previous.getFinallyStatements() == null) &&
            (previous.getCatchClauses() != null) && !previous.getCatchClauses().isEmpty()) {
            // 'try { ...; v = A; } catch (...) { ...; v = B; } yield v;'
            int uses = 1;

            if (!endsWithAssignmentOf(previous.getTryStatements(), localVariable)) {
                return -1;
            }
            uses++;

            for (TryStatement.CatchClause catchClause : previous.getCatchClauses()) {
                if (!endsWithAssignmentOf(catchClause.getStatements(), localVariable)) {
                    return -1;
                }
                uses++;
            }

            return uses;
        }

        return -1;
    }

    protected static void yieldValues(Statements statements) {
        int size = statements.size();
        Statement previous = statements.get(size - 2);

        statements.removeLast();

        if (previous.isTryStatement()) {
            yieldLastValue(previous.getTryStatements());
            for (TryStatement.CatchClause catchClause : previous.getCatchClauses()) {
                yieldLastValue(catchClause.getStatements());
            }
        } else {
            yieldLastValue(statements);
        }
    }

    @SuppressWarnings("unchecked")
    protected static void yieldLastValue(BaseStatement statements) {
        Statements list = (Statements)statements;
        Expression assignment = list.getLast().getExpression();

        list.set(list.size() - 1, new YieldStatement(assignment.getLineNumber(), assignment.getRightExpression()));
    }

    protected static boolean isYieldOf(Statement statement, AbstractLocalVariable localVariable) {
        return statement.isYieldStatement() && isReference(statement.getExpression(), localVariable);
    }

    protected static boolean isAssignmentOf(Statement statement, AbstractLocalVariable localVariable) {
        if (!statement.isExpressionStatement()) {
            return false;
        }

        Expression expression = statement.getExpression();

        return expression.isBinaryOperatorExpression() && "=".equals(expression.getOperator()) &&
               isReference(expression.getLeftExpression(), localVariable) && (countReferences(expression.getRightExpression(), localVariable) == 0);
    }

    protected static boolean endsWithAssignmentOf(BaseStatement statements, AbstractLocalVariable localVariable) {
        if ((statements == null) || !statements.isList() || (statements.size() == 0)) {
            return false;
        }

        List<Statement> list = statements.getList();

        // The other statements do not use the variable
        return isAssignmentOf(list.get(list.size() - 1), localVariable) &&
               (countReferences(list.subList(0, list.size() - 1), localVariable) == 0);
    }

    /** The first switch statement (or expression) with a block using the variable */
    protected static class SearchSwitchVisitor extends AbstractJavaSyntaxVisitor {
        protected final AbstractLocalVariable localVariable;
        protected SwitchStatement switchStatement;

        public SearchSwitchVisitor(AbstractLocalVariable localVariable) {
            this.localVariable = localVariable;
        }

        @Override
        public void visit(SwitchStatement statement) {
            if (switchStatement != null) {
                return;
            }

            for (SwitchStatement.Block block : statement.getBlocks()) {
                BaseStatement statements = block.getStatements();

                if ((statements != null) && statements.isList() && (countReferences(statements.getList(), localVariable) > 0)) {
                    switchStatement = statement;
                    return;
                }
            }

            super.visit(statement);
        }
    }
}
