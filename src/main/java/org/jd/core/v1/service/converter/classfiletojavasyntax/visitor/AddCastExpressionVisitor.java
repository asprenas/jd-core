/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.visitor;

import org.jd.core.v1.model.javasyntax.AbstractJavaSyntaxVisitor;
import org.jd.core.v1.model.javasyntax.declaration.*;
import org.jd.core.v1.model.javasyntax.expression.*;
import org.jd.core.v1.model.javasyntax.reference.InnerObjectReference;
import org.jd.core.v1.model.javasyntax.reference.ObjectReference;
import org.jd.core.v1.model.javasyntax.statement.*;
import org.jd.core.v1.model.javasyntax.type.*;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.ClassFileBodyDeclaration;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.ClassFileConstructorDeclaration;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.ClassFileMethodDeclaration;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.ClassFileStaticInitializerDeclaration;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.expression.*;
import org.jd.core.v1.service.converter.classfiletojavasyntax.util.TypeMaker;
import org.jd.core.v1.util.DefaultList;

import java.util.Map;

import static org.jd.core.v1.model.javasyntax.declaration.Declaration.FLAG_BRIDGE;
import static org.jd.core.v1.model.javasyntax.declaration.Declaration.FLAG_SYNTHETIC;
import static org.jd.core.v1.model.javasyntax.type.PrimitiveType.TYPE_BYTE;

public class AddCastExpressionVisitor extends AbstractJavaSyntaxVisitor {
    protected SearchFirstLineNumberVisitor searchFirstLineNumberVisitor = new SearchFirstLineNumberVisitor();

    protected TypeMaker typeMaker;
    protected Map<String, BaseType> typeBounds;
    /** The type parameters of the invoked generic method whose arguments are updated */
    protected BaseTypeParameter methodTypeParameters;
    protected Type returnedType;
    protected BaseType exceptionTypes;
    protected Type type;

    public AddCastExpressionVisitor(TypeMaker typeMaker) {
        this.typeMaker = typeMaker;
    }

    @Override
    public void visit(BodyDeclaration declaration) {
        BaseMemberDeclaration memberDeclarations = declaration.getMemberDeclarations();

        if (memberDeclarations != null) {
            Map<String, BaseType> tb = typeBounds;

            typeBounds = ((ClassFileBodyDeclaration)declaration).getTypeBounds();
            memberDeclarations.accept(this);
            typeBounds = tb;
        }
    }

    @Override
    public void visit(FieldDeclaration declaration) {
        if ((declaration.getFlags() & FLAG_SYNTHETIC) == 0) {
            Type t = type;

            type = declaration.getType();
            declaration.getFieldDeclarators().accept(this);
            type = t;
        }
    }

    @Override
    public void visit(FieldDeclarator declarator) {
        VariableInitializer variableInitializer = declarator.getVariableInitializer();

        if (variableInitializer != null) {
            int extraDimension = declarator.getDimension();

            if (extraDimension == 0) {
                variableInitializer.accept(this);
            } else {
                Type t = type;

                type = type.createType(type.getDimension() + extraDimension);
                variableInitializer.accept(this);
                type = t;
            }
        }
    }


    @Override
    public void visit(StaticInitializerDeclaration declaration) {
        BaseStatement statements = declaration.getStatements();

        if (statements != null) {
            Map<String, BaseType> tb = typeBounds;

            typeBounds = ((ClassFileStaticInitializerDeclaration)declaration).getTypeBounds();
            statements.accept(this);
            typeBounds = tb;
        }
    }

    @Override
    public void visit(ConstructorDeclaration declaration) {
        if ((declaration.getFlags() & (FLAG_SYNTHETIC|FLAG_BRIDGE)) == 0) {
            BaseStatement statements = declaration.getStatements();

            if (statements != null) {
                Map<String, BaseType> tb = typeBounds;
                BaseType et = exceptionTypes;

                typeBounds = ((ClassFileConstructorDeclaration) declaration).getTypeBounds();
                exceptionTypes = declaration.getExceptionTypes();
                statements.accept(this);
                typeBounds = tb;
                exceptionTypes = et;
            }
        }
    }

    @Override
    public void visit(MethodDeclaration declaration) {
        if ((declaration.getFlags() & (FLAG_SYNTHETIC|FLAG_BRIDGE)) == 0) {
            BaseStatement statements = declaration.getStatements();

            if (statements != null) {
                Map<String, BaseType> tb = typeBounds;
                Type rt = returnedType;
                BaseType et = exceptionTypes;

                typeBounds = ((ClassFileMethodDeclaration) declaration).getTypeBounds();
                returnedType = declaration.getReturnedType();
                exceptionTypes = declaration.getExceptionTypes();
                statements.accept(this);
                typeBounds = tb;
                returnedType = rt;
                exceptionTypes = et;
            }
        }
    }

    @Override
    public void visit(LambdaIdentifiersExpression expression) {
        BaseStatement statements = expression.getStatements();

        if (statements != null) {
            Type rt = returnedType;

            returnedType = ObjectType.TYPE_OBJECT;
            statements.accept(this);
            returnedType = rt;
        }
    }

    @Override
    public void visit(ReturnExpressionStatement statement) {
        statement.setExpression(updateExpression(returnedType, statement.getExpression(), false, true));
    }

    @Override
    public void visit(ThrowStatement statement) {
        if ((exceptionTypes != null) && (exceptionTypes.size() == 1)) {
            Type exceptionType = exceptionTypes.getFirst();

            if (exceptionType.isGenericType() && !statement.getExpression().getType().equals(exceptionType)) {
                statement.setExpression(addCastExpression(exceptionType, statement.getExpression()));
            }
        }
    }

    @Override
    public void visit(LocalVariableDeclaration declaration) {
        Type t = type;

        type = declaration.getType();
        declaration.getLocalVariableDeclarators().accept(this);
        type = t;
    }

    @Override
    public void visit(LocalVariableDeclarator declarator) {
        VariableInitializer variableInitializer = declarator.getVariableInitializer();

        if (variableInitializer != null) {
            int extraDimension = declarator.getDimension();

            if (extraDimension == 0) {
                variableInitializer.accept(this);
            } else {
                Type t = type;

                type = type.createType(type.getDimension() + extraDimension);
                variableInitializer.accept(this);
                type = t;
            }
        }
    }

    @Override
    public void visit(ArrayVariableInitializer declaration) {
        if (type.getDimension() == 0) {
            acceptListDeclaration(declaration);
        } else {
            Type t = type;

            type = type.createType(type.getDimension() - 1);
            acceptListDeclaration(declaration);
            type = t;
        }
    }

    @Override
    public void visit(ExpressionVariableInitializer declaration) {
        Expression expression = declaration.getExpression();

        if (expression.isNewInitializedArray()) {
            NewInitializedArray nia = (NewInitializedArray)expression;
            Type t = type;

            type = nia.getType();
            nia.getArrayInitializer().accept(this);
            type = t;
        } else {
            declaration.setExpression(updateExpression(type, expression, false, true));
        }
    }

    @Override
    public void visit(SuperConstructorInvocationExpression expression) {
        BaseExpression parameters = expression.getParameters();

        if ((parameters != null) && (parameters.size() > 0)) {
            boolean unique = typeMaker.matchCount(expression.getObjectType().getInternalName(), "<init>", parameters.size(), true) <= 1;
            boolean forceCast = !unique && (typeMaker.matchCount(typeBounds, expression.getObjectType().getInternalName(), "<init>", parameters, true) > 1);
            expression.setParameters(updateParameters(((ClassFileSuperConstructorInvocationExpression)expression).getParameterTypes(), parameters, forceCast, unique));
        }
    }

    @Override
    public void visit(ConstructorInvocationExpression expression) {
        BaseExpression parameters = expression.getParameters();

        if ((parameters != null) && (parameters.size() > 0)) {
            boolean unique = typeMaker.matchCount(expression.getObjectType().getInternalName(), "<init>", parameters.size(), true) <= 1;
            boolean forceCast = !unique && (typeMaker.matchCount(typeBounds, expression.getObjectType().getInternalName(), "<init>", parameters, true) > 1);
            expression.setParameters(updateParameters(((ClassFileConstructorInvocationExpression)expression).getParameterTypes(), parameters, forceCast, unique));
        }
    }

    @Override
    public void visit(MethodInvocationExpression expression) {
        BaseExpression parameters = expression.getParameters();

        if ((parameters != null) && (parameters.size() > 0)) {
            boolean unique = typeMaker.matchCount(expression.getInternalTypeName(), expression.getName(), parameters.size(), false) <= 1;
            boolean forceCast = !unique && (typeMaker.matchCount(typeBounds, expression.getInternalTypeName(), expression.getName(), parameters, false) > 1);
            BaseTypeParameter mtp = methodTypeParameters;
            methodTypeParameters = ((ClassFileMethodInvocationExpression)expression).getTypeParameters();
            // The parameter types of the descriptor of a generic method: the bound type parameters may select another overload
            BaseType erasedTypes = (methodTypeParameters == null) ? null : typeMaker.makeMethodTypes(expression.getDescriptor()).parameterTypes;
            expression.setParameters(updateParameters(((ClassFileMethodInvocationExpression)expression).getParameterTypes(), erasedTypes, parameters, forceCast, unique));
            methodTypeParameters = mtp;
            addCaptureRawCasts((ClassFileMethodInvocationExpression)expression);
        }

        expression.getExpression().accept(this);
    }

    /**
     * 'bound.getSupertype((Class)supertype)' with 'TypeToken&lt;? super T&gt; bound' and 'getSupertype(Class&lt;? super T&gt;)': the
     * type argument of the receiver is a capture, a parameterized argument can not fit the parameter, only a raw cast does.
     */
    private void addCaptureRawCasts(ClassFileMethodInvocationExpression expression) {
        Type receiverType = expression.getExpression().getType();

        if (!receiverType.isObjectType() || !containsWildcard(((ObjectType)receiverType).getTypeArguments())) {
            return;
        }

        TypeMaker.TypeTypes typeTypes = typeMaker.makeTypeTypes(((ObjectType)receiverType).getInternalName());

        if ((typeTypes == null) || (typeTypes.typeParameters == null)) {
            return;
        }

        java.util.HashSet<String> names = new java.util.HashSet<>();

        for (TypeParameter typeParameter : typeTypes.typeParameters.isList() ? (TypeParameters)typeTypes.typeParameters : java.util.Collections.singletonList((TypeParameter)typeTypes.typeParameters.getFirst())) {
            names.add(typeParameter.getIdentifier());
        }

        BaseExpression parameters = expression.getParameters();
        BaseType types = expression.getParameterTypes();

        for (int i = 0; i < parameters.size(); i++) {
            Type type = types.isList() ? types.getList().get(i) : types.getFirst();
            Expression parameter = parameters.isList() ? parameters.getList().get(i) : parameters.getFirst();

            if (type.isObjectType() && containsTypeVariable(((ObjectType)type).getTypeArguments(), names) &&
                    parameter.getType().isObjectType() && (((ObjectType)parameter.getType()).getTypeArguments() != null) && !parameter.isCastExpression()) {
                searchFirstLineNumberVisitor.init();
                parameter.accept(searchFirstLineNumberVisitor);
                Expression cast = new CastExpression(searchFirstLineNumberVisitor.getLineNumber(), ((ObjectType)type).createType(null), parameter);

                if (parameters.isList()) {
                    parameters.getList().set(i, cast);
                } else {
                    expression.setParameters(cast);
                }
            }
        }
    }

    private static boolean containsWildcard(BaseTypeArgument typeArguments) {
        if (typeArguments == null) {
            return false;
        }
        for (TypeArgument typeArgument : typeArguments.isTypeArgumentList() ? typeArguments.getTypeArgumentList() : java.util.Collections.singletonList(typeArguments.getTypeArgumentFirst())) {
            if ((typeArgument == WildcardTypeArgument.WILDCARD_TYPE_ARGUMENT) || (typeArgument instanceof WildcardSuperTypeArgument) || (typeArgument instanceof WildcardExtendsTypeArgument)) {
                return true;
            }
        }
        return false;
    }

    /** @return true if a type argument is, or bounds a wildcard with, one of the type variables */
    private static boolean containsTypeVariable(BaseTypeArgument typeArguments, java.util.Set<String> names) {
        if (typeArguments == null) {
            return false;
        }
        for (TypeArgument typeArgument : typeArguments.isTypeArgumentList() ? typeArguments.getTypeArgumentList() : java.util.Collections.singletonList(typeArguments.getTypeArgumentFirst())) {
            if (typeArgument instanceof WildcardSuperTypeArgument) {
                typeArgument = ((WildcardSuperTypeArgument)typeArgument).getType();
            } else if (typeArgument instanceof WildcardExtendsTypeArgument) {
                typeArgument = ((WildcardExtendsTypeArgument)typeArgument).getType();
            }
            if ((typeArgument instanceof GenericType) && names.contains(((GenericType)typeArgument).getName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void visit(NewExpression expression) {
        BaseExpression parameters = expression.getParameters();

        if (parameters != null) {
            boolean unique = typeMaker.matchCount(expression.getObjectType().getInternalName(), "<init>", parameters.size(), true) <= 1;
            boolean forceCast = !unique && (typeMaker.matchCount(typeBounds, expression.getObjectType().getInternalName(), "<init>", parameters, true) > 1);
            expression.setParameters(updateParameters(((ClassFileNewExpression)expression).getParameterTypes(), parameters, forceCast, unique));
        }
    }

    @Override
    public void visit(NewInitializedArray expression) {
        ArrayVariableInitializer arrayInitializer = expression.getArrayInitializer();

        if (arrayInitializer != null) {
            Type t = type;

            type = expression.getType();
            arrayInitializer.accept(this);
            type = t;
        }
    }

    @Override
    public void visit(FieldReferenceExpression expression) {
        Expression exp = expression.getExpression();

        if ((exp != null) && !exp.isObjectTypeReferenceExpression()) {
            Type type = typeMaker.makeFromInternalTypeName(expression.getInternalTypeName());

            if (type.getName() != null) {
                expression.setExpression(updateExpression(type, exp, false, true));
            }
        }
    }

    @Override
    public void visit(BinaryOperatorExpression expression) {
        expression.getLeftExpression().accept(this);

        Expression rightExpression = expression.getRightExpression();

        if (expression.getOperator().equals("=")) {
            if (rightExpression.isMethodInvocationExpression()) {
                ClassFileMethodInvocationExpression mie = (ClassFileMethodInvocationExpression)rightExpression;

                if (mie.getTypeParameters() != null) {
                    // Do not add cast expression if method contains type parameters
                    rightExpression.accept(this);
                    return;
                }
            }

            expression.setRightExpression(updateExpression(expression.getLeftExpression().getType(), rightExpression, false, true));
            return;
        }

        rightExpression.accept(this);
    }

    @Override
    public void visit(TernaryOperatorExpression expression) {
        Type expressionType = expression.getType();

        expression.getCondition().accept(this);
        expression.setTrueExpression(updateExpression(expressionType, expression.getTrueExpression(), false, true));
        expression.setFalseExpression(updateExpression(expressionType, expression.getFalseExpression(), false, true));
    }

    @SuppressWarnings("unchecked")
    protected BaseExpression updateParameters(BaseType types, BaseExpression expressions, boolean forceCast, boolean unique) {
        return updateParameters(types, null, expressions, forceCast, unique);
    }

    protected BaseExpression updateParameters(BaseType types, BaseType erasedTypes, BaseExpression expressions, boolean forceCast, boolean unique) {
        if (expressions != null) {
            if (expressions.isList()) {
                DefaultList<Type> typeList = types.getList();
                DefaultList<Expression> expressionList = expressions.getList();

                for (int i = expressionList.size() - 1; i >= 0; i--) {
                    expressionList.set(i, updateParameter(typeList.get(i), erasedType(erasedTypes, i), expressionList.get(i), forceCast, unique));
                }
            } else {
                expressions = updateParameter(types.getFirst(), erasedType(erasedTypes, 0), expressions.getFirst(), forceCast, unique);
            }
        }

        return expressions;
    }

    private static Type erasedType(BaseType erasedTypes, int index) {
        if ((erasedTypes == null) || (index >= erasedTypes.size())) {
            return null;
        }
        return erasedTypes.isList() ? erasedTypes.getList().get(index) : erasedTypes.getFirst();
    }

    /** @return true if the parameter needs a cast to the erasure of the parameter type of the invoked generic method */
    private static boolean isErasedParameterCast(Type type, Type erasedType, Expression expression) {
        return (erasedType != null) && erasedType.isObjectType() && type.isObjectType() && !expression.isNullExpression() &&
               expression.getType().isObjectType() && !((ObjectType)erasedType).rawEquals((ObjectType)type) &&
               !((ObjectType)erasedType).rawEquals((ObjectType)expression.getType());
    }

    /**
     * '(StringBuffer)applyRules(c, (Appendable)buf)' with '&lt;B extends Appendable&gt; B applyRules(Calendar, B)': a parameter
     * cast to its erasure binds the type parameter of the returned type to the erasure, the invocation takes the erased
     * returned type of the descriptor.
     */
    private void updateErasedReturnedType(ClassFileMethodInvocationExpression expression) {
        BaseExpression parameters = expression.getParameters();

        if ((expression.getTypeParameters() == null) || (parameters == null) || (parameters.size() == 0) || !expression.getType().isObjectType()) {
            return;
        }
        if ((typeMaker.matchCount(expression.getInternalTypeName(), expression.getName(), parameters.size(), false) <= 1) ||
                (typeMaker.matchCount(typeBounds, expression.getInternalTypeName(), expression.getName(), parameters, false) <= 1)) {
            return;
        }

        TypeMaker.MethodTypes methodTypes = typeMaker.makeMethodTypes(expression.getDescriptor());
        BaseType types = expression.getParameterTypes();

        for (int i = 0; i < parameters.size(); i++) {
            Type type = types.isList() ? types.getList().get(i) : types.getFirst();
            Expression parameter = parameters.isList() ? parameters.getList().get(i) : parameters.getFirst();

            if (isErasedParameterCast(type, erasedType(methodTypes.parameterTypes, i), parameter)) {
                if (methodTypes.returnedType.isObjectType() && !((ObjectType)methodTypes.returnedType).rawEquals((ObjectType)expression.getType())) {
                    expression.setType(methodTypes.returnedType);
                }
                return;
            }
        }
    }

    private Expression updateParameter(Type type, Type erasedType, Expression expression, boolean forceCast, boolean unique) {
        if (!unique && (erasedType != null) && erasedType.isObjectType() && type.isObjectType() && isWrapperType((ObjectType)type) &&
                !erasedType.equals(type) && isBoxing(expression)) {
            // 'use((Object)1)' with 'use(Integer)' and '<T> use(T)': the type parameter bound to 'Integer' would select
            // 'use(Integer)', the erasure of the parameter keeps the generic method
            Expression parameter = expression.getParameters().getFirst();
            parameter.accept(this);
            searchFirstLineNumberVisitor.init();
            expression.accept(searchFirstLineNumberVisitor);
            return new CastExpression(searchFirstLineNumberVisitor.getLineNumber(), erasedType, parameter);
        }

        if (forceCast && isErasedParameterCast(type, erasedType, expression)) {
            // 'appendTo((Appendable)builder, parts)' with '<A extends Appendable> appendTo(A, Iterator)' and
            // 'appendTo(StringBuilder, Iterator)': the type parameter bound to 'StringBuilder' would select the other overload
            expression.accept(this);
            searchFirstLineNumberVisitor.init();
            expression.accept(searchFirstLineNumberVisitor);
            return new CastExpression(searchFirstLineNumberVisitor.getLineNumber(), erasedType, expression);
        }

        expression = updateExpression(type, expression, forceCast, unique);

        if (type == TYPE_BYTE) {
            if (expression.isIntegerConstantExpression()) {
                expression = new CastExpression(TYPE_BYTE, expression);
            } else if (expression.isTernaryOperatorExpression()) {
                Expression exp = expression.getTrueExpression();

                if (exp.isIntegerConstantExpression() || exp.isTernaryOperatorExpression()) {
                    expression = new CastExpression(TYPE_BYTE, expression);
                } else {
                    exp = expression.getFalseExpression();

                    if (exp.isIntegerConstantExpression() || exp.isTernaryOperatorExpression()) {
                        expression = new CastExpression(TYPE_BYTE, expression);
                    }
                }
            }
        }

        return expression;
    }

    private Expression updateExpression(Type type, Expression expression, boolean forceCast, boolean unique) {
        if (expression.isNullExpression()) {
            if (forceCast) {
                searchFirstLineNumberVisitor.init();
                expression.accept(searchFirstLineNumberVisitor);
                expression = new CastExpression(searchFirstLineNumberVisitor.getLineNumber(), type, expression);
            }
        } else {
            if (expression instanceof ClassFileMethodInvocationExpression) {
                updateErasedReturnedType((ClassFileMethodInvocationExpression)expression);
            }

            Type expressionType = expression.getType();

            if (type.isObjectType() && (((ObjectType)type).getTypeArguments() != null) && expression.isMethodInvocationExpression() &&
                    ((ClassFileMethodInvocationExpression)expression).isRawCast()) {
                // '(Comparator)Ordering.natural()': a raw cast stops the inference of the type arguments from the target type
                expression.accept(this);
                searchFirstLineNumberVisitor.init();
                expression.accept(searchFirstLineNumberVisitor);
                return new CastExpression(searchFirstLineNumberVisitor.getLineNumber(), ((ObjectType)type).createType(null), expression);
            }

            if (!unique && type.isObjectType() && !isWrapperType((ObjectType)type) && isBoxing(expression)) {
                // 'eq((Short)expected, actual)' with 'eq(short, short)' and 'eq(Object, Object)': AutoboxingVisitor
                // removes 'Short.valueOf(expected)', the cast keeps the boxing that selects the overload
                Expression parameter = expression.getParameters().getFirst();
                parameter.accept(this);
                searchFirstLineNumberVisitor.init();
                expression.accept(searchFirstLineNumberVisitor);
                return new CastExpression(searchFirstLineNumberVisitor.getLineNumber(), expressionType, parameter);
            }

            if (!expressionType.equals(type)) {
                if (type.isObjectType()) {
                    if (expressionType.isObjectType()) {
                        ObjectType objectType = (ObjectType) type;
                        ObjectType expressionObjectType = (ObjectType) expressionType;

                        if (forceCast && !objectType.rawEquals(expressionObjectType)) {
                            // Force disambiguation of method invocation => Add cast
                            if (expression.isNewExpression()) {
                                ClassFileNewExpression ne = (ClassFileNewExpression)expression;
                                ne.setObjectType(ne.getObjectType().createType(null));
                            }
                            expression = addCastExpression(objectType, expression);
                        } else if (isRawTypeAssignableGenericMethodInvocation(objectType, expressionObjectType, expression)) {
                            // 'collect(Collectors.toList())': the type arguments of a generic method are inferred from
                            // the parameter type (target typing), a raw cast would hide them
                        } else if (((expression instanceof MethodReferenceExpression) && !expression.isMethodInvocationExpression()) || (expression instanceof AbstractLambdaExpression)) {
                            // 'map(Object::toString)': a functional expression takes the target type
                        } else if (unique && containsMethodTypeParameter(objectType) && typeMaker.isRawTypeAssignable(objectType, expressionObjectType)) {
                            // 'get(Iterable<T> iterable, ...)' with 'Iterable<? extends T>': 'T' of the invoked method is
                            // inferred from the argument, a raw cast would infer 'Object'
                        } else if (!ObjectType.TYPE_OBJECT.equals(type) && !typeMaker.isAssignable(typeBounds, objectType, expressionObjectType) &&
                                   !isWildcardAssignable(objectType, expressionObjectType)) {
                            BaseTypeArgument ta1 = objectType.getTypeArguments();
                            BaseTypeArgument ta2 = expressionObjectType.getTypeArguments();
                            Type t = type;

                            if ((ta1 != null) && (ta2 != null) && !ta1.isTypeArgumentAssignableFrom(typeBounds, ta2)) {
                                // Incompatible typeArgument arguments => Add cast
                                t = objectType.createType(null);
                            }
                            expression = addCastExpression(t, expression);
                        }
                    } else if (expressionType.isGenericType() && !ObjectType.TYPE_OBJECT.equals(type) && !isBoundAssignable((ObjectType)type, expressionType)) {
                        expression = addCastExpression(type, expression);
                    }
                } else if (type.isGenericType()) {
                    if (expressionType.isObjectType() || expressionType.isGenericType()) {
                        expression = addCastExpression(type, expression);
                    }
                }
            }

            if (expression.isCastExpression() && (((CastExpression)expression).getTypeAnnotationReferences() == null)) {
                // An annotated cast '(@A String)o' is kept
                if (expression.getExpression() instanceof ClassFileMethodInvocationExpression) {
                    updateErasedReturnedType((ClassFileMethodInvocationExpression)expression.getExpression());
                }

                Type ceExpressionType = expression.getExpression().getType();

                if (type.isObjectType() && ceExpressionType.isObjectType()) {
                    ObjectType ot1 = (ObjectType)type;
                    ObjectType ot2 = (ObjectType)ceExpressionType;

                    if (ot1.equals(ot2)) {
                        // Remove cast expression
                        expression = expression.getExpression();
                    } else if (unique && typeMaker.isAssignable(typeBounds, ot1, ot2)) {
                        // Remove cast expression
                        expression = expression.getExpression();
                    }
                }
            }

            expression.accept(this);
        }

        return expression;
    }

    /** @return true if the bound of the type variable 'C extends Comparable' fits 'Comparable' or 'Comparable&lt;?&gt;' */
    private boolean isBoundAssignable(ObjectType type, Type genericType) {
        if (ObjectType.TYPE_OBJECT.getInternalName().equals(type.getInternalName()) && (type.getDimension() <= genericType.getDimension())) {
            // 'max(a, b, c, rest)' with 'E... rest' and 'Object[]': an array of type variables is an 'Object[]'
            return true;
        }

        BaseTypeArgument typeArguments = type.getTypeArguments();

        if ((typeArguments != null) && (typeArguments != WildcardTypeArgument.WILDCARD_TYPE_ARGUMENT)) {
            return false;
        }

        BaseType bound = (typeBounds == null) ? null : typeBounds.get(genericType.getName());

        return (bound != null) && !bound.isList() && bound.isObjectType() && (((Type)bound).getDimension() == genericType.getDimension()) &&
               typeMaker.isRawTypeAssignable(type, (ObjectType)bound);
    }

    /** @return true if a type argument of 'type' is a type parameter of the invoked generic method */
    private boolean containsMethodTypeParameter(ObjectType type) {
        if ((methodTypeParameters == null) || (type.getTypeArguments() == null)) {
            return false;
        }

        java.util.HashSet<String> names = new java.util.HashSet<>();

        for (TypeParameter typeParameter : methodTypeParameters.isList() ? (TypeParameters)methodTypeParameters : java.util.Collections.singletonList((TypeParameter)methodTypeParameters.getFirst())) {
            names.add(typeParameter.getIdentifier());
        }

        for (TypeArgument typeArgument : typeArgumentList(type.getTypeArguments())) {
            if (typeArgument.isWildcardExtendsTypeArgument() || typeArgument.isWildcardSuperTypeArgument()) {
                typeArgument = typeArgument.getType();
            }
            if (typeArgument.isGenericTypeArgument() && names.contains(((GenericType)typeArgument).getName())) {
                return true;
            }
        }

        return false;
    }

    /** @return true for 'Supplier<? extends InputStream>' and 'Supplier<FileInputStream>' */
    private boolean isWildcardAssignable(ObjectType type, ObjectType expressionType) {
        BaseTypeArgument typeArguments = type.getTypeArguments();

        if ((typeArguments == null) || (expressionType.getDimension() != type.getDimension())) {
            return false;
        }

        ObjectType ot = typeMaker.searchSuperParameterizedType(type, expressionType);

        if ((ot == null) || !ot.getInternalName().equals(type.getInternalName()) || (ot.getTypeArguments() == null)) {
            return false;
        }

        java.util.List<TypeArgument> list1 = typeArgumentList(typeArguments);
        java.util.List<TypeArgument> list2 = typeArgumentList(ot.getTypeArguments());

        if (list1.size() != list2.size()) {
            return false;
        }

        boolean wildcard = false;

        for (int i = 0; i < list1.size(); i++) {
            TypeArgument ta1 = list1.get(i);
            TypeArgument ta2 = list2.get(i);

            if (ta1.equals(ta2) || (ta1 == WildcardTypeArgument.WILDCARD_TYPE_ARGUMENT)) {
                continue;
            }
            if (ta1.isWildcardSuperTypeArgument()) {
                // 'Function<? super Integer, ...>' and 'Function<Integer, ...>'
                Type bound = ta1.getType();

                if (!(ta2 instanceof Type) || !bound.isObjectType() || !((Type)ta2).isObjectType() || !typeMaker.isAssignable(typeBounds, (ObjectType)ta2, (ObjectType)bound)) {
                    return false;
                }

                wildcard = true;
                continue;
            }
            if (!ta1.isWildcardExtendsTypeArgument()) {
                return false;
            }

            Type bound = ta1.getType();
            Type t2 = ta2.isWildcardExtendsTypeArgument() ? ta2.getType() : (ta2 instanceof Type) ? (Type)ta2 : null;

            if ((t2 == null) || !bound.isObjectType() || !t2.isObjectType() || !typeMaker.isAssignable(typeBounds, (ObjectType)bound, (ObjectType)t2)) {
                return false;
            }

            wildcard = true;
        }

        return wildcard;
    }

    private static java.util.List<TypeArgument> typeArgumentList(BaseTypeArgument typeArguments) {
        return typeArguments.isTypeArgumentList() ? typeArguments.getTypeArgumentList() : java.util.Collections.singletonList(typeArguments.getTypeArgumentFirst());
    }

    private static boolean isWrapperType(ObjectType type) {
        return (type.getDimension() == 0) && AutoboxingVisitor.VALUEOF_DESCRIPTOR_MAP.containsKey(type.getInternalName());
    }

    /** 'Short.valueOf(s)' with the descriptor '(S)Ljava/lang/Short;', removed by AutoboxingVisitor */
    private static boolean isBoxing(Expression expression) {
        return expression.isMethodInvocationExpression() && expression.getExpression().isObjectTypeReferenceExpression() &&
               expression.getName().equals("valueOf") && (expression.getParameters() != null) && (expression.getParameters().size() == 1) &&
               expression.getDescriptor().equals(AutoboxingVisitor.VALUEOF_DESCRIPTOR_MAP.get(expression.getInternalTypeName()));
    }

    /** An invocation of a generic method whose raw type fits: only its type arguments differ */
    private boolean isRawTypeAssignableGenericMethodInvocation(ObjectType type, ObjectType expressionType, Expression expression) {
        return expression.isMethodInvocationExpression() && (((ClassFileMethodInvocationExpression)expression).getTypeParameters() != null) &&
               (type.getTypeArguments() != null) && typeMaker.isRawTypeAssignable(type, expressionType);
    }

    private Expression addCastExpression(Type type, Expression expression) {
        if (expression.isCastExpression()) {
            if (type.equals(expression.getExpression().getType())) {
                return expression.getExpression();
            } else {
                CastExpression ce = (CastExpression)expression;

                ce.setType(type);
                return ce;
            }
        } else {
            searchFirstLineNumberVisitor.init();
            expression.accept(searchFirstLineNumberVisitor);
            return new CastExpression(searchFirstLineNumberVisitor.getLineNumber(), type, expression);
        }
    }

    @Override public void visit(FloatConstantExpression expression) {}
    @Override public void visit(IntegerConstantExpression expression) {}
    @Override public void visit(ConstructorReferenceExpression expression) {}
    @Override public void visit(DoubleConstantExpression expression) {}
    @Override public void visit(EnumConstantReferenceExpression expression) {}
    @Override public void visit(LocalVariableReferenceExpression expression) {}
    @Override public void visit(LongConstantExpression expression) {}
    @Override public void visit(BreakStatement statement) {}
    @Override public void visit(ByteCodeStatement statement) {}
    @Override public void visit(ContinueStatement statement) {}
    @Override public void visit(NullExpression expression) {}
    @Override public void visit(ObjectTypeReferenceExpression expression) {}
    @Override public void visit(SuperExpression expression) {}
    @Override public void visit(ThisExpression expression) {}
    @Override public void visit(TypeReferenceDotClassExpression expression) {}
    @Override public void visit(ObjectReference reference) {}
    @Override public void visit(InnerObjectReference reference) {}
    @Override public void visit(TypeArguments type) {}
    @Override public void visit(WildcardExtendsTypeArgument type) {}
    @Override public void visit(ObjectType type) {}
    @Override public void visit(InnerObjectType type) {}
    @Override public void visit(WildcardSuperTypeArgument type) {}
    @Override public void visit(Types list) {}
    @Override public void visit(TypeParameterWithTypeBounds type) {}
}
