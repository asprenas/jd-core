/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package org.jd.core.v1.model.javasyntax.expression;

import org.jd.core.v1.model.javasyntax.type.Type;
import org.jd.core.v1.util.DefaultList;

/**
 * Java 21+ record pattern 'Point(int x, int y)' (a 'case' label or the right side of 'instanceof'). The
 * component patterns are record patterns or type patterns ('Type name', or '_' without type and binding).
 */
public class RecordPatternExpression extends AbstractLineNumberTypeExpression {
    protected DefaultList<Expression> components;

    public RecordPatternExpression(int lineNumber, Type type, DefaultList<Expression> components) {
        super(lineNumber, type);
        this.components = components;
    }

    public DefaultList<Expression> getComponents() {
        return components;
    }

    @Override
    public boolean isRecordPatternExpression() { return true; }

    @Override
    public void accept(ExpressionVisitor visitor) {
        visitor.visit(this);
    }

    @Override
    public String toString() {
        return "RecordPatternExpression{" + type + " " + components + "}";
    }
}
