/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package org.jd.core.v1.model.javasyntax.statement;

import org.jd.core.v1.model.javasyntax.expression.Expression;

/** Java 14+ 'yield' statement: the value of a switch expression block. */
public class YieldStatement implements Statement {
    protected int lineNumber;
    protected Expression expression;

    public YieldStatement(Expression expression) {
        this(expression.getLineNumber(), expression);
    }

    public YieldStatement(int lineNumber, Expression expression) {
        this.lineNumber = lineNumber;
        this.expression = expression;
    }

    @Override
    public int getLineNumber() {
        return lineNumber;
    }

    @Override
    public Expression getExpression() {
        return expression;
    }

    public void setExpression(Expression expression) {
        this.expression = expression;
    }

    @Override
    public boolean isYieldStatement() { return true; }

    @Override
    public void accept(StatementVisitor visitor) {
        visitor.visit(this);
    }

    @Override
    public String toString() {
        return "YieldStatement{yield " + expression + "}";
    }
}
