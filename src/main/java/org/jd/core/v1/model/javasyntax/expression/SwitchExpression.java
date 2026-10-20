/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package org.jd.core.v1.model.javasyntax.expression;

import org.jd.core.v1.model.javasyntax.statement.SwitchStatement;
import org.jd.core.v1.model.javasyntax.type.Type;

/**
 * Java 14+ switch expression. The selector and the case blocks are kept in a SwitchStatement, the value of
 * each block is given by a YieldStatement (or the block throws).
 */
public class SwitchExpression extends AbstractLineNumberTypeExpression {
    protected SwitchStatement switchStatement;

    public SwitchExpression(int lineNumber, Type type, SwitchStatement switchStatement) {
        super(lineNumber, type);
        this.switchStatement = switchStatement;
    }

    public SwitchStatement getSwitchStatement() {
        return switchStatement;
    }

    @Override
    public int getPriority() {
        // Same as a ternary operator: parenthesized when used as an operand
        return 15;
    }

    @Override
    public boolean isSwitchExpression() { return true; }

    @Override
    public void accept(ExpressionVisitor visitor) {
        visitor.visit(this);
    }

    @Override
    public String toString() {
        return "SwitchExpression{" + switchStatement + "}";
    }
}
