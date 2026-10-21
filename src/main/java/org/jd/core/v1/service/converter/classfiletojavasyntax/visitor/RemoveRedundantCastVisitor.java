/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.visitor;

import org.jd.core.v1.model.javasyntax.expression.*;
import org.jd.core.v1.model.javasyntax.type.ObjectType;

/**
 * Removes the casts of the expressions that already have the cast type: javac adds 'checkcast' after the methods
 * returning a type variable ('(String)entry.getKey()'), and the type of the invocation is known only after the
 * binding of the type arguments.
 * <p>
 * Only the casts of method invocations and field references, the 'checkcast' of javac for the type variables: the
 * type of a local variable is inferred from its uses ('Class<?> c' unified with 'Class<? extends A>'), the casts of
 * lambdas, method references and 'null' give them their type. The types must be equal: a cast to a super type
 * ('(Object)s') may select another overloaded method. The annotated casts are kept.
 */
public class RemoveRedundantCastVisitor extends AbstractUpdateExpressionVisitor {
    @Override
    protected Expression updateExpression(Expression expression) {
        while (isRedundantCast(expression)) {
            expression = expression.getExpression();
        }
        return expression;
    }

    protected static boolean isRedundantCast(Expression expression) {
        if (!expression.isCastExpression()) {
            return false;
        }

        CastExpression ce = (CastExpression)expression;
        Expression operand = ce.getExpression();

        if (!ce.isExplicit() || (ce.getTypeAnnotationReferences() != null) || !ce.getType().isObjectType()) {
            return false;
        }
        if (!operand.isMethodInvocationExpression() && !operand.isFieldReferenceExpression()) {
            // The type of a local variable is inferred from its uses and may not be its declared type
            return false;
        }
        if ("java/lang/Class".equals(((ObjectType)ce.getType()).getInternalName())) {
            // 'getClass()' returns 'Class<? extends |T|>' (JLS 4.3.2), 'ObjectType.equals' ignores the wildcards of 'Class'
            return false;
        }

        return ce.getType().equals(operand.getType());
    }
}
