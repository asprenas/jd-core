/*
 * Copyright (c) 2008-2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.model.javasyntax.expression;

import org.jd.core.v1.model.javasyntax.reference.TypeAnnotationReferences;
import org.jd.core.v1.model.javasyntax.type.BaseTypeArgument;
import org.jd.core.v1.model.javasyntax.type.Type;

public class MethodReferenceExpression extends AbstractLineNumberTypeExpression {
    protected TypeAnnotationReferences typeAnnotationReferences;
    // Explicit type arguments of a generic method or constructor: 'new <String>G("x")', 'G::<String>m'
    protected BaseTypeArgument nonWildcardTypeArguments;
    protected Expression expression;
    protected String internalTypeName;
    protected String name;
    protected String descriptor;

    public MethodReferenceExpression(Type type, Expression expression, String internalTypeName, String name, String descriptor) {
        super(type);
        this.expression = expression;
        this.internalTypeName = internalTypeName;
        this.name = name;
        this.descriptor = descriptor;
    }

    public MethodReferenceExpression(int lineNumber, Type type, Expression expression, String internalTypeName, String name, String descriptor) {
        super(lineNumber, type);
        this.expression = expression;
        this.internalTypeName = internalTypeName;
        this.name = name;
        this.descriptor = descriptor;
    }

    @Override
    public Expression getExpression() {
        return expression;
    }

    public void setExpression(Expression expression) {
        this.expression = expression;
    }

    @Override
    public String getInternalTypeName() {
        return internalTypeName;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescriptor() {
        return descriptor;
    }

    public BaseTypeArgument getNonWildcardTypeArguments() {
        return nonWildcardTypeArguments;
    }

    public void setNonWildcardTypeArguments(BaseTypeArgument nonWildcardTypeArguments) {
        this.nonWildcardTypeArguments = nonWildcardTypeArguments;
    }

    public TypeAnnotationReferences getTypeAnnotationReferences() {
        return typeAnnotationReferences;
    }

    public void setTypeAnnotationReferences(TypeAnnotationReferences typeAnnotationReferences) {
        this.typeAnnotationReferences = typeAnnotationReferences;
    }

    @Override
    public void accept(ExpressionVisitor visitor) {
        visitor.visit(this);
    }
}
