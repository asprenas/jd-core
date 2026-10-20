/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.expression;

import org.jd.core.v1.model.javasyntax.expression.Expression;
import org.jd.core.v1.model.javasyntax.expression.TernaryOperatorExpression;
import org.jd.core.v1.model.javasyntax.type.ObjectType;
import org.jd.core.v1.model.javasyntax.type.Type;

import java.util.List;

/**
 * Index of the matching 'case' label computed by SwitchBootstraps.typeSwitch / enumSwitch, written as a chain
 * of ternary operators with the same result. It keeps the labels so the 'switch' can be rewritten with
 * pattern labels ('case Integer i', 'case null', ...).
 */
public class ClassFileSwitchIndexExpression extends TernaryOperatorExpression {
    protected Expression selector;
    protected Expression restart;
    protected List<Label> labels;

    public ClassFileSwitchIndexExpression(TernaryOperatorExpression expression, Expression selector, Expression restart, List<Label> labels) {
        super(expression.getLineNumber(), expression.getType(), expression.getCondition(), expression.getTrueExpression(), expression.getFalseExpression());
        this.selector = selector;
        this.restart = restart;
        this.labels = labels;
    }

    public Expression getSelector() {
        return selector;
    }

    public Expression getRestart() {
        return restart;
    }

    public List<Label> getLabels() {
        return labels;
    }

    /** A 'case' label: a type pattern (type), or a constant (value: an expression). */
    public static class Label {
        protected ObjectType type;
        protected Expression value;

        public Label(ObjectType type, Expression value) {
            this.type = type;
            this.value = value;
        }

        public boolean isTypePattern() {
            return value == null;
        }

        public ObjectType getType() {
            return type;
        }

        public Expression getValue() {
            return value;
        }
    }
}
