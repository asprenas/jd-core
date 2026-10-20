/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package org.jd.core.v1.model.javasyntax.expression;

import org.jd.core.v1.model.javasyntax.type.Type;

/**
 * Java 16+ type pattern 'Type name' (a 'case' label or the right side of 'instanceof'). The binding is a local
 * variable reference, so a later renaming of the variable is printed; without binding, '_' (Java 22+).
 */
public class TypePatternExpression extends AbstractLineNumberTypeExpression {
    protected Expression variable;

    public TypePatternExpression(int lineNumber, Type type, Expression variable) {
        super(lineNumber, type);
        this.variable = variable;
    }

    public Expression getVariable() {
        return variable;
    }

    @Override
    public boolean isTypePatternExpression() { return true; }

    @Override
    public void accept(ExpressionVisitor visitor) {
        visitor.visit(this);
    }

    @Override
    public String toString() {
        return "TypePatternExpression{" + type + " " + variable + "}";
    }
}
