/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.util;

import org.jd.core.v1.model.javasyntax.AbstractJavaSyntaxVisitor;
import org.jd.core.v1.model.javasyntax.expression.BinaryOperatorExpression;
import org.jd.core.v1.model.javasyntax.expression.Expression;
import org.jd.core.v1.model.javasyntax.expression.LocalVariableReferenceExpression;
import org.jd.core.v1.model.javasyntax.expression.MethodInvocationExpression;
import org.jd.core.v1.model.javasyntax.expression.NullExpression;
import org.jd.core.v1.model.javasyntax.statement.*;
import org.jd.core.v1.model.javasyntax.type.ObjectType;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.expression.ClassFileLocalVariableReferenceExpression;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.expression.ClassFileMethodInvocationExpression;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.statement.ClassFileTryStatement;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.localvariable.AbstractLocalVariable;
import org.jd.core.v1.util.DefaultList;

import java.util.List;

public class TryWithResourcesStatementMaker {

    public static Statement make(
            LocalVariableMaker localVariableMaker, Statements statements, Statements tryStatements,
            DefaultList<TryStatement.CatchClause> catchClauses, Statements finallyStatements, int majorVersion) {
        int size = statements.size();

        if ((finallyStatements == null) || (finallyStatements.size() == 0)) {
            return parsePatternInlinedClose(localVariableMaker, statements, tryStatements, catchClauses, majorVersion);
        }

        if ((size < 2) || (finallyStatements.size() != 1) || !checkThrowable(catchClauses)) {
            return null;
        }

        Statement statement = statements.get(size - 2);

        if (!statement.isExpressionStatement()) {
            return null;
        }

        Expression expression = statement.getExpression();

        if (!expression.isBinaryOperatorExpression()) {
            return null;
        }

        Expression boe = expression;

        expression = boe.getLeftExpression();

        if (!expression.isLocalVariableReferenceExpression()) {
            return null;
        }

        AbstractLocalVariable lv1 = ((ClassFileLocalVariableReferenceExpression) expression).getLocalVariable();

        statement = statements.get(size - 1);

        if (!statement.isExpressionStatement()) {
            return null;
        }

        expression = statement.getExpression();

        if (!expression.isBinaryOperatorExpression()) {
            return null;
        }

        expression = expression.getLeftExpression();

        if (!expression.isLocalVariableReferenceExpression()) {
            return null;
        }

        AbstractLocalVariable lv2 = ((ClassFileLocalVariableReferenceExpression) expression).getLocalVariable();

        statement = finallyStatements.getFirst();

        if (statement.isIfStatement() && (lv1 == getLocalVariable(statement.getCondition()))) {
            statement = statement.getStatements().getFirst();

            if (statement.isIfElseStatement()) {
                return parsePatternAddSuppressed(localVariableMaker, statements, tryStatements, finallyStatements, boe, lv1, lv2, statement);
            }
            if (statement.isExpressionStatement()) {
                return parsePatternCloseResource(localVariableMaker, statements, tryStatements, finallyStatements, boe, lv1, lv2, statement);
            }
        }

        if (statement.isExpressionStatement()) {
            return parsePatternCloseResource(localVariableMaker, statements, tryStatements, finallyStatements, boe, lv1, lv2, statement);
        }

        return null;
    }

    protected static Statement parsePatternAddSuppressed(
            LocalVariableMaker localVariableMaker, Statements statements, Statements tryStatements,
            Statements finallyStatements, Expression boe, AbstractLocalVariable lv1, AbstractLocalVariable lv2,
            Statement statement) {
        if (!statement.isIfElseStatement()) {
            return null;
        }

        Statement ies = statement;

        statement = ies.getStatements().getFirst();

        if (!statement.isTryStatement()) {
            return null;
        }

        Statement ts = statement;

        statement = ies.getElseStatements().getFirst();

        if (!statement.isExpressionStatement()) {
            return null;
        }

        Expression expression = statement.getExpression();

        if (!expression.isMethodInvocationExpression()) {
            return null;
        }

        MethodInvocationExpression mie = (MethodInvocationExpression) expression;

        if ((ts.getFinallyStatements() != null) || (lv2 != getLocalVariable(ies.getCondition())) ||
                !checkThrowable(ts.getCatchClauses()) || !checkCloseInvocation(mie, lv1)) {
            return null;
        }

        statement = ts.getTryStatements().getFirst();

        if (!statement.isExpressionStatement()) {
            return null;
        }

        expression = statement.getExpression();

        if (!expression.isMethodInvocationExpression()) {
            return null;
        }

        mie = (MethodInvocationExpression) expression;

        if (!checkCloseInvocation(mie, lv1)) {
            return null;
        }

        statement = ts.getCatchClauses().getFirst().getStatements().getFirst();

        if (!statement.isExpressionStatement()) {
            return null;
        }

        expression = statement.getExpression();

        if (!expression.isMethodInvocationExpression()) {
            return null;
        }

        mie = (MethodInvocationExpression) expression;

        if (!mie.getName().equals("addSuppressed") || !mie.getDescriptor().equals("(Ljava/lang/Throwable;)V")) {
            return null;
        }

        expression = mie.getExpression();

        if (!expression.isLocalVariableReferenceExpression()) {
            return null;
        }
        if (((ClassFileLocalVariableReferenceExpression) expression).getLocalVariable() != lv2) {
            return null;
        }

        return newTryStatement(localVariableMaker, statements, tryStatements, finallyStatements, boe, lv1, lv2);
    }

    protected static boolean checkThrowable(List<? extends TryStatement.CatchClause> catchClauses) {
        return (catchClauses.size() == 1) && catchClauses.get(0).getType().equals(ObjectType.TYPE_THROWABLE);
    }

    protected static AbstractLocalVariable getLocalVariable(Expression condition) {
        if (!condition.isBinaryOperatorExpression()) {
            return null;
        }

        if (!condition.getOperator().equals("!=") || !condition.getRightExpression().isNullExpression() || !condition.getLeftExpression().isLocalVariableReferenceExpression()) {
            return null;
        }

        return ((ClassFileLocalVariableReferenceExpression) condition.getLeftExpression()).getLocalVariable();
    }

    protected static boolean checkCloseInvocation(MethodInvocationExpression mie, AbstractLocalVariable lv) {
        if (mie.getName().equals("close") && mie.getDescriptor().equals("()V")) {
            Expression expression = mie.getExpression();

            if (expression.isLocalVariableReferenceExpression()) {
                return ((ClassFileLocalVariableReferenceExpression) expression).getLocalVariable() == lv;
            }
        }

        return false;
    }

    protected static Statement parsePatternCloseResource(
            LocalVariableMaker localVariableMaker, Statements statements, Statements tryStatements, Statements finallyStatements,
            Expression boe, AbstractLocalVariable lv1, AbstractLocalVariable lv2, Statement statement) {
        Expression expression = statement.getExpression();

        if (!expression.isMethodInvocationExpression()) {
            return null;
        }

        MethodInvocationExpression mie = (MethodInvocationExpression) expression;

        if (!mie.getName().equals("$closeResource") || !mie.getDescriptor().equals("(Ljava/lang/Throwable;Ljava/lang/AutoCloseable;)V")) {
            return null;
        }

        DefaultList<Expression> parameters = mie.getParameters().getList();
        Expression parameter0 = parameters.getFirst();

        if (!parameter0.isLocalVariableReferenceExpression()) {
            return null;
        }
        if (((ClassFileLocalVariableReferenceExpression)parameter0).getLocalVariable() != lv2) {
            return null;
        }

        Expression parameter1 = parameters.get(1);

        if (!parameter1.isLocalVariableReferenceExpression()) {
            return null;
        }
        if (((ClassFileLocalVariableReferenceExpression)parameter1).getLocalVariable() != lv1) {
            return null;
        }

        return newTryStatement(localVariableMaker, statements, tryStatements, finallyStatements, boe, lv1, lv2);
    }

    /**
     * JDK 11+ javac inlines the calls to 'close()':
     * <pre>
     * r = expression;
     * try {
     *     ...
     *     if (r != null) r.close();      // before each exit, 'if' omitted when 'r' can not be null
     *     return x;
     * } catch (Throwable t) {
     *     if (r != null) try { r.close(); } catch (Throwable t2) { t.addSuppressed(t2); }
     *     throw t;
     * }
     * </pre>
     * The calls are removed from the body. When the body does not read 'r', the resource is the copied variable
     * or field: 'try (expression)' (Java 9+), otherwise 'try (Type r = expression)'.
     */
    protected static Statement parsePatternInlinedClose(
            LocalVariableMaker localVariableMaker, Statements statements, Statements tryStatements,
            DefaultList<TryStatement.CatchClause> catchClauses, int majorVersion) {
        if ((statements.size() < 1) || (tryStatements == null) || !checkThrowable(catchClauses)) {
            return null;
        }

        // 'r = expression;'
        Statement statement = statements.getLast();

        if (!statement.isExpressionStatement()) {
            return null;
        }

        Expression boe = statement.getExpression();

        if (!boe.isBinaryOperatorExpression() || !boe.getOperator().equals("=") || !boe.getLeftExpression().isLocalVariableReferenceExpression()) {
            return null;
        }

        AbstractLocalVariable resource = ((ClassFileLocalVariableReferenceExpression)boe.getLeftExpression()).getLocalVariable();

        if (!(resource.getType() instanceof ObjectType) || !checkInlinedCloseHandler((ClassFileTryStatement.CatchClause)catchClauses.getFirst(), resource)) {
            return null;
        }

        // The calls to 'close()' of the body: each one before an exit, or at the end of the body
        DefaultList<Statements> containers = new DefaultList<>();
        DefaultList<Integer> indexes = new DefaultList<>();

        collectInlinedCloses(tryStatements, resource, true, containers, indexes);

        CloseCountVisitor closeCountVisitor = new CloseCountVisitor(resource);
        tryStatements.accept(closeCountVisitor);

        if (closeCountVisitor.count != containers.size()) {
            // Another call to 'close()', a call not followed by an exit, ...
            return null;
        }

        for (int i = containers.size() - 1; i >= 0; i--) {
            containers.get(i).remove((int)indexes.get(i));
        }

        removeEmptyBranches(tryStatements);
        statements.removeLast();

        Expression expression = boe.getRightExpression();
        CloseCountVisitor referenceVisitor = new CloseCountVisitor(resource);
        tryStatements.accept(referenceVisitor);

        DefaultList<TryStatement.Resource> resources = new DefaultList<>();

        if ((majorVersion >= 53) && (referenceVisitor.references == 0) && (expression.isLocalVariableReferenceExpression() || expression.isFieldReferenceExpression())) {
            // 'try (reader)': javac copied the variable
            localVariableMaker.removeLocalVariable(resource);
            resources.add(new TryStatement.Resource(null, null, expression));
        } else {
            resource.setDeclared(true);
            TryStatement.Resource r = new TryStatement.Resource((ObjectType)resource.getType(), resource.getName(), expression);
            r.setTypeAnnotationReferences(resource.getTypeAnnotationReferences());
            resources.add(r);
        }

        return new ClassFileTryStatement(resources, tryStatements, null, null, false, false);
    }

    /** '[if (r != null)] try { r.close(); } catch (Throwable t2) { t.addSuppressed(t2); } throw t;' */
    protected static boolean checkInlinedCloseHandler(ClassFileTryStatement.CatchClause catchClause, AbstractLocalVariable resource) {
        BaseStatement handler = catchClause.getStatements();

        if ((handler == null) || (handler.size() != 2)) {
            return false;
        }

        AbstractLocalVariable throwable = catchClause.getLocalVariable();
        Statement statement = handler.getList().get(0);

        if (statement.isIfStatement()) {
            if ((resource != getLocalVariable(statement.getCondition())) || (statement.getStatements() == null) || (statement.getStatements().size() != 1)) {
                return false;
            }
            statement = statement.getStatements().getFirst();
        }

        if (!statement.isTryStatement() || (statement.getFinallyStatements() != null) || !checkThrowable(statement.getCatchClauses())) {
            return false;
        }

        BaseStatement closeStatements = statement.getTryStatements();

        if ((closeStatements.size() != 1) || !isClose(closeStatements.getFirst(), resource)) {
            return false;
        }

        ClassFileTryStatement.CatchClause cc = (ClassFileTryStatement.CatchClause)statement.getCatchClauses().get(0);
        BaseStatement suppressStatements = cc.getStatements();

        if ((suppressStatements == null) || (suppressStatements.size() != 1) || !suppressStatements.getFirst().isExpressionStatement()) {
            return false;
        }

        Expression expression = suppressStatements.getFirst().getExpression();

        if (!expression.isMethodInvocationExpression() || !((MethodInvocationExpression)expression).getName().equals("addSuppressed") ||
                !isLocalVariable(expression.getExpression(), throwable) || !isLocalVariable(expression.getParameters().getFirst(), cc.getLocalVariable())) {
            return false;
        }

        statement = handler.getList().get(1);
        return statement.isThrowStatement() && isLocalVariable(statement.getExpression(), throwable);
    }

    /** 'r.close();' */
    protected static boolean isClose(Statement statement, AbstractLocalVariable resource) {
        return statement.isExpressionStatement() && statement.getExpression().isMethodInvocationExpression() &&
               checkCloseInvocation((MethodInvocationExpression)statement.getExpression(), resource);
    }

    /** 'r.close();' or 'if (r != null) r.close();' */
    protected static boolean isInlinedClose(Statement statement, AbstractLocalVariable resource) {
        if (statement.isIfStatement()) {
            return (resource == getLocalVariable(statement.getCondition())) && (statement.getStatements() != null) &&
                   (statement.getStatements().size() == 1) && isClose(statement.getStatements().getFirst(), resource);
        }
        return isClose(statement, resource);
    }

    protected static boolean isLocalVariable(Expression expression, AbstractLocalVariable localVariable) {
        return expression.isLocalVariableReferenceExpression() && (((ClassFileLocalVariableReferenceExpression)expression).getLocalVariable() == localVariable);
    }

    protected static boolean isExit(Statement statement) {
        return statement.isReturnStatement() || statement.isReturnExpressionStatement() || statement.isThrowStatement() ||
               statement.isBreakStatement() || statement.isContinueStatement();
    }

    /**
     * 'if (c) { r.close(); } else { ... }' at the end of the body, the 'continue' after 'close()' being implicit:
     * without 'close()', 'if (c) {} else { ... }' is 'if (!c) { ... }'
     */
    protected static void removeEmptyBranches(BaseStatement statements) {
        if ((statements == null) || !statements.isList() || (statements.size() == 0)) {
            return;
        }

        Statements list = (Statements)statements;
        Statement last = list.getLast();

        if (last.isIfElseStatement()) {
            removeEmptyBranches(last.getStatements());
            removeEmptyBranches(last.getElseStatements());

            if (isEmpty(last.getStatements())) {
                list.set(list.size() - 1, new IfStatement(SwitchStatementMaker.negate(last.getCondition()), last.getElseStatements()));
            } else if (isEmpty(last.getElseStatements())) {
                list.set(list.size() - 1, new IfStatement(last.getCondition(), last.getStatements()));
            }
        } else if (last.isIfStatement()) {
            removeEmptyBranches(last.getStatements());
        }
    }

    protected static boolean isEmpty(BaseStatement statements) {
        return (statements == null) || (statements.isList() && (statements.size() == 0));
    }

    protected static void collectInlinedCloses(BaseStatement statements, AbstractLocalVariable resource, boolean body,
                                               DefaultList<Statements> containers, DefaultList<Integer> indexes) {
        if ((statements == null) || !statements.isList()) {
            if ((statements != null) && (statements.size() == 1)) {
                collectInlinedCloses(statements.getFirst(), resource, containers, indexes);
            }
            return;
        }

        Statements list = (Statements)statements;
        int size = list.size();

        for (int i = 0; i < size; i++) {
            Statement statement = list.get(i);

            if (isInlinedClose(statement, resource)) {
                boolean lastOfBody = body && (i == size - 1);

                if (lastOfBody || ((i + 1 < size) && isExit(list.get(i + 1)))) {
                    containers.add(list);
                    indexes.add(i);
                }
            } else if (body && (i == size - 1) && (statement.isIfStatement() || statement.isIfElseStatement())) {
                // The ends of the branches of the last 'if' are ends of the body
                collectInlinedCloses(statement.getStatements(), resource, true, containers, indexes);
                collectInlinedCloses(statement.getElseStatements(), resource, true, containers, indexes);
            } else {
                collectInlinedCloses(statement, resource, containers, indexes);
            }
        }
    }

    protected static void collectInlinedCloses(Statement statement, AbstractLocalVariable resource,
                                               DefaultList<Statements> containers, DefaultList<Integer> indexes) {
        if (statement.isTryStatement()) {
            collectInlinedCloses(statement.getTryStatements(), resource, false, containers, indexes);
            for (TryStatement.CatchClause cc : statement.getCatchClauses() == null ? new DefaultList<TryStatement.CatchClause>() : statement.getCatchClauses()) {
                collectInlinedCloses(cc.getStatements(), resource, false, containers, indexes);
            }
            collectInlinedCloses(statement.getFinallyStatements(), resource, false, containers, indexes);
        } else {
            // Some statements are their own 'getStatements()'
            if (statement.getStatements() != statement) {
                collectInlinedCloses(statement.getStatements(), resource, false, containers, indexes);
            }
            if (statement.getElseStatements() != statement) {
                collectInlinedCloses(statement.getElseStatements(), resource, false, containers, indexes);
            }
        }
    }

    /** Counts the calls 'r.close()' and the other references to 'r' */
    protected static class CloseCountVisitor extends AbstractJavaSyntaxVisitor {
        protected final AbstractLocalVariable resource;
        protected int count;
        protected int references;

        public CloseCountVisitor(AbstractLocalVariable resource) {
            this.resource = resource;
        }

        @Override
        public void visit(MethodInvocationExpression expression) {
            if (checkCloseInvocation(expression, resource)) {
                count++;
            }
            super.visit(expression);
        }

        @Override
        public void visit(LocalVariableReferenceExpression expression) {
            if (isLocalVariable(expression, resource)) {
                references++;
            }
        }
    }

    protected static ClassFileTryStatement newTryStatement(
            LocalVariableMaker localVariableMaker, Statements statements, Statements tryStatements,
            Statements finallyStatements, Expression boe, AbstractLocalVariable lv1, AbstractLocalVariable lv2) {

        // Remove resource & synthetic local variables
        statements.removeLast();
        statements.removeLast();
        lv1.setDeclared(true);
        localVariableMaker.removeLocalVariable(lv2);

        // Create try-with-resources statement
        DefaultList<TryStatement.Resource> resources = new DefaultList<>();

        TryStatement.Resource resource = new TryStatement.Resource((ObjectType) lv1.getType(), lv1.getName(), boe.getRightExpression());
        resource.setTypeAnnotationReferences(lv1.getTypeAnnotationReferences());
        resources.add(resource);

        return new ClassFileTryStatement(resources, tryStatements, null, finallyStatements, false, false);
    }
}
