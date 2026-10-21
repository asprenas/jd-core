/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.util;

import org.jd.core.v1.model.javasyntax.AbstractJavaSyntaxVisitor;
import org.jd.core.v1.model.javasyntax.declaration.LocalVariableDeclarator;
import org.jd.core.v1.model.javasyntax.expression.LocalVariableReferenceExpression;
import org.jd.core.v1.model.javasyntax.expression.NewExpression;
import org.jd.core.v1.model.javasyntax.statement.BaseStatement;
import org.jd.core.v1.model.javasyntax.statement.LocalVariableDeclarationStatement;
import org.jd.core.v1.model.javasyntax.statement.Statement;
import org.jd.core.v1.model.javasyntax.statement.TypeDeclarationStatement;

import java.util.List;

/**
 * Java 22+ unnamed variables '_' ('unnamedVariables' configuration, off by default: the names of the unused variables
 * are in the class file, the source may not use '_').
 * <p>
 * A variable is unnamed when no expression of its scope has its name. The test is conservative: a variable of an
 * anonymous or local class with the same name, or an assignment, keeps the name, and a scope containing an anonymous
 * or a local class keeps all the names (the captured variables of their bodies are not seen).
 */
public class UnnamedVariableUtil {
    public static final int JAVA_22 = 66;
    public static final String UNNAMED = "_";

    public static boolean isReferenced(BaseStatement scope, String name) {
        if (scope == null) {
            return false;
        }

        ReferenceVisitor visitor = new ReferenceVisitor(name);
        scope.accept(visitor);
        return visitor.referenced;
    }

    public static boolean isReferenced(List<Statement> scope, String name) {
        ReferenceVisitor visitor = new ReferenceVisitor(name);

        for (Statement statement : scope) {
            statement.accept(visitor);
        }
        return visitor.referenced;
    }

    /** 'T x = init;': one variable with an initializer */
    public static LocalVariableDeclarator getUnnamedCandidate(Statement statement) {
        if (statement.isLocalVariableDeclarationStatement()) {
            LocalVariableDeclarationStatement declaration = (LocalVariableDeclarationStatement)statement;

            if (declaration.getLocalVariableDeclarators() instanceof LocalVariableDeclarator) {
                LocalVariableDeclarator declarator = (LocalVariableDeclarator)declaration.getLocalVariableDeclarators();

                if ((declarator.getVariableInitializer() != null) && (declarator.getDimension() == 0)) {
                    return declarator;
                }
            }
        }
        return null;
    }

    protected static class ReferenceVisitor extends AbstractJavaSyntaxVisitor {
        protected final String name;
        protected boolean referenced;

        public ReferenceVisitor(String name) {
            this.name = name;
        }

        @Override
        public void visit(LocalVariableReferenceExpression expression) {
            referenced |= name.equals(expression.getName());
        }

        @Override
        public void visit(LocalVariableDeclarator declarator) {
            // Another variable with the same name: do not guess which one the references are
            referenced |= name.equals(declarator.getName());
            super.visit(declarator);
        }

        @Override
        public void visit(NewExpression expression) {
            referenced |= (expression.getBodyDeclaration() != null);
            super.visit(expression);
        }

        @Override
        public void visit(TypeDeclarationStatement statement) {
            referenced = true;
        }
    }
}
