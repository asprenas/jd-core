/*
 * Copyright (c) 2008-2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.visitor;

import org.jd.core.v1.model.javasyntax.AbstractJavaSyntaxVisitor;
import org.jd.core.v1.model.javasyntax.declaration.BaseLocalVariableDeclarator;
import org.jd.core.v1.model.javasyntax.declaration.ExpressionVariableInitializer;
import org.jd.core.v1.model.javasyntax.expression.Expression;
import org.jd.core.v1.model.javasyntax.expression.LocalVariableReferenceExpression;
import org.jd.core.v1.model.javasyntax.statement.BaseStatement;
import org.jd.core.v1.model.javasyntax.statement.LocalVariableDeclarationStatement;
import org.jd.core.v1.model.javasyntax.statement.Statement;
import org.jd.core.v1.model.javasyntax.statement.Statements;
import org.jd.core.v1.model.javasyntax.statement.SwitchStatement;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.ClassFileLocalVariableDeclarator;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.expression.ClassFileLocalVariableReferenceExpression;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.localvariable.AbstractLocalVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * 'String upper; return switch (s) { case "c" -> { upper = s.toUpperCase(); yield upper; } ... };': a variable
 * declared without value and only used in one block of a switch is declared in the block, at its first assignment:
 * 'case "c" -> { String upper = s.toUpperCase(); yield upper; }'.
 */
public class MoveDeclarationIntoSwitchBlockVisitor extends AbstractJavaSyntaxVisitor {
    @Override
    @SuppressWarnings("unchecked")
    public void visit(Statements list) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).isLocalVariableDeclarationStatement() && moveIntoSwitchBlock(list, i)) {
                list.remove(i--);
            }
        }

        super.visit(list);
    }

    @SuppressWarnings("unchecked")
    protected static boolean moveIntoSwitchBlock(Statements list, int index) {
        LocalVariableDeclarationStatement declaration = (LocalVariableDeclarationStatement)list.get(index);
        BaseLocalVariableDeclarator declarators = declaration.getLocalVariableDeclarators();

        if (!(declarators instanceof ClassFileLocalVariableDeclarator) || (((ClassFileLocalVariableDeclarator)declarators).getVariableInitializer() != null)) {
            return false;
        }

        AbstractLocalVariable localVariable = ((ClassFileLocalVariableDeclarator)declarators).getLocalVariable();
        List<Statement> following = list.subList(index + 1, list.size());
        int count = countReferences(following, localVariable);

        if (count == 0) {
            return false;
        }

        SearchSwitchBlocksVisitor searchSwitchBlocksVisitor = new SearchSwitchBlocksVisitor();

        for (Statement statement : following) {
            statement.accept(searchSwitchBlocksVisitor);
        }

        for (BaseStatement blockStatements : searchSwitchBlocksVisitor.blocks) {
            if ((blockStatements == null) || !blockStatements.isList() || (countReferences(blockStatements.getList(), localVariable) != count)) {
                continue;
            }

            Statements statements = (Statements)blockStatements;

            for (int i = 0; i < statements.size(); i++) {
                Statement statement = statements.get(i);

                if (countReferences(statement, localVariable) == 0) {
                    continue;
                }

                // The first statement using the variable assigns it
                Expression expression = statement.isExpressionStatement() ? statement.getExpression() : null;

                if ((expression == null) || !expression.isBinaryOperatorExpression() || !"=".equals(expression.getOperator()) ||
                    !isReference(expression.getLeftExpression(), localVariable) || (countReferences(expression.getRightExpression(), localVariable) != 0)) {
                    break;
                }

                declaration.setLocalVariableDeclarators(new ClassFileLocalVariableDeclarator(
                    expression.getLineNumber(), localVariable, new ExpressionVariableInitializer(expression.getRightExpression())));
                statements.set(i, declaration);
                return true;
            }
        }

        return false;
    }

    protected static boolean isReference(Expression expression, AbstractLocalVariable localVariable) {
        return expression.isLocalVariableReferenceExpression() && (expression instanceof ClassFileLocalVariableReferenceExpression) &&
               (((ClassFileLocalVariableReferenceExpression)expression).getLocalVariable() == localVariable);
    }

    protected static int countReferences(List<Statement> statements, AbstractLocalVariable localVariable) {
        CountReferencesVisitor visitor = new CountReferencesVisitor(localVariable);

        for (Statement statement : statements) {
            statement.accept(visitor);
        }
        return visitor.count;
    }

    protected static int countReferences(Statement statement, AbstractLocalVariable localVariable) {
        CountReferencesVisitor visitor = new CountReferencesVisitor(localVariable);
        statement.accept(visitor);
        return visitor.count;
    }

    protected static int countReferences(Expression expression, AbstractLocalVariable localVariable) {
        CountReferencesVisitor visitor = new CountReferencesVisitor(localVariable);
        expression.accept(visitor);
        return visitor.count;
    }

    protected static class CountReferencesVisitor extends AbstractJavaSyntaxVisitor {
        protected final AbstractLocalVariable localVariable;
        protected int count;

        public CountReferencesVisitor(AbstractLocalVariable localVariable) {
            this.localVariable = localVariable;
        }

        @Override
        public void visit(LocalVariableReferenceExpression expression) {
            if (isReference(expression, localVariable)) {
                count++;
            }
        }

        @Override
        public void visit(LocalVariableDeclarationStatement statement) {
            if (statement.getLocalVariableDeclarators() instanceof ClassFileLocalVariableDeclarator &&
                (((ClassFileLocalVariableDeclarator)statement.getLocalVariableDeclarators()).getLocalVariable() == localVariable)) {
                count++;
            }
            super.visit(statement);
        }
    }

    /** The statements of the blocks of the switch statements and expressions, the enclosing blocks first */
    protected static class SearchSwitchBlocksVisitor extends AbstractJavaSyntaxVisitor {
        protected final List<BaseStatement> blocks = new ArrayList<>();

        @Override
        public void visit(SwitchStatement.LabelBlock statement) {
            blocks.add(statement.getStatements());
            super.visit(statement);
        }

        @Override
        public void visit(SwitchStatement.MultiLabelsBlock statement) {
            blocks.add(statement.getStatements());
            super.visit(statement);
        }
    }
}
