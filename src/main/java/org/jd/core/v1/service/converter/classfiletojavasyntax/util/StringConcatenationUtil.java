/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.util;

import org.jd.core.v1.model.javasyntax.expression.*;
import org.jd.core.v1.model.javasyntax.type.ObjectType;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.expression.ClassFileMethodInvocationExpression;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.expression.ClassFileNewExpression;

import java.util.Iterator;

public class StringConcatenationUtil {

    public static Expression create(Expression expression, int lineNumber, String typeName) {
        if (expression.isMethodInvocationExpression()) {
            MethodInvocationExpression mie = (MethodInvocationExpression) expression;

            if ((mie.getParameters() != null) && !mie.getParameters().isList() && "append".equals(mie.getName())) {
                Expression concatenatedStringExpression = mie.getParameters().getFirst();
                Expression expr = mie.getExpression();
                Expression firstOperand = concatenatedStringExpression;
                Expression secondOperand = null;
                boolean firstParameterHaveGenericType = false;

                while (expr.isMethodInvocationExpression()) {
                    mie = (MethodInvocationExpression) expr;

                    if ((mie.getParameters() == null) || mie.getParameters().isList() || !"append".equals(mie.getName())) {
                        break;
                    }

                    secondOperand = firstOperand;
                    firstOperand = mie.getParameters().getFirst();
                    firstParameterHaveGenericType = firstOperand.getType().isGenericType();
                    concatenatedStringExpression = new BinaryOperatorExpression(mie.getLineNumber(), ObjectType.TYPE_STRING, (Expression) mie.getParameters(), "+", concatenatedStringExpression, 4);
                    expr = mie.getExpression();
                }

                if (expr.isNewExpression()) {
                    String internalTypeName = expr.getType().getDescriptor();

                    if ("Ljava/lang/StringBuilder;".equals(internalTypeName) || "Ljava/lang/StringBuffer;".equals(internalTypeName)) {
                        if (expr.getParameters() == null) {
                            if (!firstParameterHaveGenericType) {
                                if (!isString(firstOperand) && ((secondOperand == null) || !isString(secondOperand))) {
                                    // 'new StringBuilder().append('{').append(key)': '{' + key is not a string
                                    // concatenation, the empty string is added in front
                                    return new BinaryOperatorExpression(firstOperand.getLineNumber(), ObjectType.TYPE_STRING, StringConstantExpression.EMPTY_STRING, "+", concatenatedStringExpression, 4);
                                }
                                return concatenatedStringExpression;
                            }
                        } else if (!expr.getParameters().isList()) {
                            expr = expr.getParameters().getFirst();

                            if (ObjectType.TYPE_STRING.equals(expr.getType())) {
                                return new BinaryOperatorExpression(expr.getLineNumber(), ObjectType.TYPE_STRING, expr, "+", concatenatedStringExpression, 4);
                            }
                        }
                    }
                }
            }
        }

        return new ClassFileMethodInvocationExpression(lineNumber, null, ObjectType.TYPE_STRING, expression, typeName, "toString", "()Ljava/lang/String;", null, null);
    }

    /**
     * Java 9+ StringConcatFactory.makeConcatWithConstants recipe: \u0001 is the next dynamic argument,
     * \u0002 the next constant (bootstrap argument), any other character is literal text.
     */
    public static Expression create(String recipe, String[] recipeConstants, BaseExpression parameters) {
        Iterator<Expression> arguments = (parameters == null) ? null : parameters.iterator();
        int constantIndex = 0;
        StringBuilder text = new StringBuilder();
        Expression expression = null;

        for (int i = 0, length = recipe.length(); i < length; i++) {
            char c = recipe.charAt(i);

            if (c == '\u0001') {
                if (text.length() > 0) {
                    expression = append(expression, new StringConstantExpression(text.toString()));
                    text.setLength(0);
                }
                Expression argument = arguments.next();
                expression = (expression == null) ? createFirstStringConcatenationItem(argument) : append(expression, argument);
            } else if (c == '\u0002') {
                text.append(recipeConstants[constantIndex++]);
            } else {
                text.append(c);
            }
        }

        if (text.length() > 0) {
            expression = append(expression, new StringConstantExpression(text.toString()));
        }

        return (expression == null) ? StringConstantExpression.EMPTY_STRING : expression;
    }

    private static Expression append(Expression left, Expression right) {
        return (left == null) ? right : new BinaryOperatorExpression(left.getLineNumber(), ObjectType.TYPE_STRING, left, "+", right, 6);
    }

    public static Expression create(BaseExpression parameters) {
        switch (parameters.size()) {
            case 0:
                return StringConstantExpression.EMPTY_STRING;
            case 1:
                return createFirstStringConcatenationItem(parameters.getFirst());
            default:
                Iterator<Expression> iterator = parameters.iterator();
                Expression expression = createFirstStringConcatenationItem(iterator.next());

                while (iterator.hasNext()) {
                    expression = new BinaryOperatorExpression(expression.getLineNumber(), ObjectType.TYPE_STRING, expression, "+", iterator.next(), 6);
                }

                return expression;
        }
    }

    private static boolean isString(Expression expression) {
        return ObjectType.TYPE_STRING.equals(expression.getType());
    }

    private static Expression createFirstStringConcatenationItem(Expression expression) {
        if (!expression.getType().equals(ObjectType.TYPE_STRING)) {
            expression = new BinaryOperatorExpression(expression.getLineNumber(), ObjectType.TYPE_STRING, StringConstantExpression.EMPTY_STRING, "+", expression, 6);
        }

        return expression;
    }
}