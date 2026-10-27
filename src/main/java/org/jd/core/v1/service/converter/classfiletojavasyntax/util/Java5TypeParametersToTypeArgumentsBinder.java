/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.util;

import org.jd.core.v1.model.javasyntax.expression.*;
import org.jd.core.v1.model.javasyntax.statement.SwitchStatement;
import org.jd.core.v1.model.javasyntax.statement.YieldStatement;
import org.jd.core.v1.model.javasyntax.type.*;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.ClassFileConstructorOrMethodDeclaration;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.expression.*;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.localvariable.AbstractLocalVariable;
import org.jd.core.v1.service.converter.classfiletojavasyntax.visitor.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static org.jd.core.v1.model.javasyntax.declaration.Declaration.FLAG_STATIC;
import static org.jd.core.v1.model.javasyntax.type.ObjectType.*;

public class Java5TypeParametersToTypeArgumentsBinder extends AbstractTypeParametersToTypeArgumentsBinder implements ExpressionVisitor {
    protected static final RemoveNonWildcardTypeArgumentsVisitor REMOVE_NON_WILDCARD_TYPE_ARGUMENTS_VISITOR = new RemoveNonWildcardTypeArgumentsVisitor();

    protected PopulateBindingsWithTypeParameterVisitor populateBindingsWithTypeParameterVisitor = new PopulateBindingsWithTypeParameterVisitor();
    protected BindTypesToTypesVisitor bindTypesToTypesVisitor = new BindTypesToTypesVisitor();
    protected SearchInTypeArgumentVisitor searchInTypeArgumentVisitor = new SearchInTypeArgumentVisitor();
    protected TypeArgumentToTypeVisitor typeArgumentToTypeVisitor = new TypeArgumentToTypeVisitor();
    protected BaseTypeToTypeArgumentVisitor baseTypeToTypeArgumentVisitor = new BaseTypeToTypeArgumentVisitor();
    protected GetTypeArgumentVisitor getTypeArgumentVisitor = new GetTypeArgumentVisitor();
    protected BindTypeParametersToNonWildcardTypeArgumentsVisitor bindTypeParametersToNonWildcardTypeArgumentsVisitor = new BindTypeParametersToNonWildcardTypeArgumentsVisitor();

    protected TypeMaker typeMaker;
    protected String internalTypeName;
    protected boolean staticMethod;
    protected PopulateBindingsWithTypeArgumentVisitor populateBindingsWithTypeArgumentVisitor;
    protected Map<String, TypeArgument> contextualBindings;
    protected Map<String, BaseType> contextualTypeBounds;

    public Java5TypeParametersToTypeArgumentsBinder(TypeMaker typeMaker, String internalTypeName, ClassFileConstructorOrMethodDeclaration comd) {
        this.typeMaker = typeMaker;
        this.internalTypeName = internalTypeName;
        this.staticMethod = ((comd.getFlags() & FLAG_STATIC) != 0);
        this.populateBindingsWithTypeArgumentVisitor = new PopulateBindingsWithTypeArgumentVisitor(typeMaker);
        this.contextualBindings = comd.getBindings();
        this.contextualTypeBounds = comd.getTypeBounds();
    }

    @Override
    public ClassFileConstructorInvocationExpression newConstructorInvocationExpression(
            int lineNumber, ObjectType objectType, String descriptor,
            TypeMaker.MethodTypes methodTypes, BaseExpression parameters) {

        Map<String, TypeArgument> bindings = new HashMap<>();
        BaseType parameterTypes = clone(methodTypes.parameterTypes);
        BaseTypeParameter methodTypeParameters = methodTypes.typeParameters;

        populateBindings(bindings, null, null, null, methodTypeParameters, TYPE_OBJECT, null, null, null);

        parameterTypes = bind(bindings, parameterTypes);
        bindParameters(parameterTypes, parameters);

        return new ClassFileConstructorInvocationExpression(lineNumber, objectType, descriptor, parameterTypes, parameters);
    }

    @Override
    public ClassFileSuperConstructorInvocationExpression newSuperConstructorInvocationExpression(
            int lineNumber, ObjectType objectType, String descriptor,
            TypeMaker.MethodTypes methodTypes, BaseExpression parameters) {

        BaseType parameterTypes = clone(methodTypes.parameterTypes);
        Map<String, TypeArgument> bindings = contextualBindings;
        TypeMaker.TypeTypes typeTypes = typeMaker.makeTypeTypes(internalTypeName);

        if ((typeTypes != null) && (typeTypes.superType != null) && (typeTypes.superType.getTypeArguments() != null)) {
            TypeMaker.TypeTypes superTypeTypes = typeMaker.makeTypeTypes(objectType.getInternalName());

            if (superTypeTypes != null) {
                bindings = new HashMap<>();
                BaseTypeParameter typeParameters = superTypeTypes.typeParameters;
                BaseTypeArgument typeArguments = typeTypes.superType.getTypeArguments();
                BaseTypeParameter methodTypeParameters = methodTypes.typeParameters;

                populateBindings(bindings, null, typeParameters, typeArguments, methodTypeParameters, TYPE_OBJECT, null, null, null);
            }
        }

        parameterTypes = bind(bindings, parameterTypes);
        bindParameters(parameterTypes, parameters);

        return new ClassFileSuperConstructorInvocationExpression(lineNumber, objectType, descriptor, parameterTypes, parameters);
    }

    @Override
    public ClassFileMethodInvocationExpression newMethodInvocationExpression(
            int lineNumber, Expression expression, ObjectType objectType, String name, String descriptor,
            TypeMaker.MethodTypes methodTypes, BaseExpression parameters) {
        return new ClassFileMethodInvocationExpression(
            lineNumber, methodTypes.typeParameters, methodTypes.returnedType, expression,
            objectType.getInternalName(), name, descriptor, clone(methodTypes.parameterTypes), parameters);
    }

    @Override
    public FieldReferenceExpression newFieldReferenceExpression(
            int lineNumber, Type type, Expression expression, ObjectType objectType, String name, String descriptor) {

        Type expressionType = expression.getType();

        if (expressionType.isObjectType()) {
            ObjectType expressionObjectType = (ObjectType) expressionType;

            // 'inverse.delegate' with 'AbstractBiMap<V, K> inverse' in 'AbstractBiMap<K, V>': the field of another instance
            // of the current type is bound with the type arguments of this instance
            if (staticMethod || !expressionObjectType.getInternalName().equals(internalTypeName) ||
                    (!expression.isThisExpression() && (expressionObjectType.getTypeArguments() != null))) {
                if (type.isObjectType()) {
                    ObjectType ot = (ObjectType) type;

                    if (ot.getTypeArguments() != null) {
                        TypeMaker.TypeTypes typeTypes = typeMaker.makeTypeTypes(expressionObjectType.getInternalName());

                        if (typeTypes == null) {
                            type = (Type)bind(contextualBindings, type);
                        } else {
                            Map<String, TypeArgument> bindings = new HashMap<>();
                            BaseTypeParameter typeParameters = typeTypes.typeParameters;
                            BaseTypeArgument typeArguments = expressionObjectType.getTypeArguments();
                            boolean partialBinding = populateBindings(bindings, expression, typeParameters, typeArguments, null, TYPE_OBJECT, null, null, null);

                            if (!partialBinding) {
                                type = (Type) bind(bindings, type);
                            }
                        }
                    }
                }
            }
        }

        return new FieldReferenceExpression(lineNumber, type, expression, objectType.getInternalName(), name, descriptor);
    }

    @Override
    public void bindParameterTypesWithArgumentTypes(Type type, Expression expression) {
        this.type = type;
        expression.accept(this);
        expression.accept(REMOVE_NON_WILDCARD_TYPE_ARGUMENTS_VISITOR);
    }

    protected Type checkTypeArguments(Type type, AbstractLocalVariable localVariable) {
        if (type.isObjectType()) {
            ObjectType objectType = (ObjectType)type;

            if (objectType.getTypeArguments() != null) {
                Type localVariableType = localVariable.getType();

                if (localVariableType.isObjectType()) {
                    ObjectType localVariableObjectType = (ObjectType)localVariableType;
                    TypeMaker.TypeTypes typeTypes = typeMaker.makeTypeTypes(localVariableObjectType.getInternalName());

                    if ((typeTypes != null) && (typeTypes.typeParameters == null)) {
                        type = ((ObjectType)type).createType(null);
                    }
                }
            }
        }

        return type;
    }

    protected void bindParameters(BaseType parameterTypes, BaseExpression parameters) {
        if (parameterTypes != null) {
            if (parameterTypes.isList() && parameters.isList()) {
                Iterator<Type> parameterTypesIterator = parameterTypes.iterator();
                Iterator<Expression> parametersIterator = parameters.iterator();

                while (parametersIterator.hasNext()) {
                    Expression parameter = parametersIterator.next();
                    this.type = parameterTypesIterator.next();
                    parameter.accept(this);
                    parameter.accept(REMOVE_NON_WILDCARD_TYPE_ARGUMENTS_VISITOR);
                }
            } else {
                Expression parameter = parameters.getFirst();
                this.type = parameterTypes.getFirst();
                parameter.accept(this);
                parameter.accept(REMOVE_NON_WILDCARD_TYPE_ARGUMENTS_VISITOR);
            }
        }
    }

    protected boolean populateBindings(
            Map<String, TypeArgument> bindings, Expression expression,
            BaseTypeParameter typeParameters, BaseTypeArgument typeArguments, BaseTypeParameter methodTypeParameters,
            Type returnType, Type returnExpressionType, BaseType parameterTypes, BaseExpression parameters) {
        Map<String, BaseType> typeBounds = new HashMap<>();
        Set<String> fixedNames = new HashSet<>();
        boolean statik = (expression != null) && expression.isObjectTypeReferenceExpression();

        if (!statik) {
            bindings.putAll(contextualBindings);

            if (typeParameters != null) {
                populateBindingsWithTypeParameterVisitor.init(bindings, typeBounds);
                typeParameters.accept(populateBindingsWithTypeParameterVisitor);

                if (typeArguments != null) {
                    if (typeParameters.isList() && typeArguments.isTypeArgumentList()) {
                        Iterator<TypeParameter> iteratorTypeParameter = typeParameters.iterator();
                        Iterator<TypeArgument> iteratorTypeArgument = typeArguments.getTypeArgumentList().iterator();

                        while (iteratorTypeParameter.hasNext()) {
                            TypeArgument typeArgument = iteratorTypeArgument.next();
                            String identifier = iteratorTypeParameter.next().getIdentifier();
                            bindings.put(identifier, typeArgument);
                            if (isFixed(typeArgument)) {
                                fixedNames.add(identifier);
                            }
                        }
                    } else {
                        TypeArgument typeArgument = typeArguments.getTypeArgumentFirst();
                        bindings.put(typeParameters.getFirst().getIdentifier(), typeArgument);
                        if (isFixed(typeArgument)) {
                            fixedNames.add(typeParameters.getFirst().getIdentifier());
                        }
                    }
                }
            }
        }

        if (methodTypeParameters != null) {
            populateBindingsWithTypeParameterVisitor.init(bindings, typeBounds);
            methodTypeParameters.accept(populateBindingsWithTypeParameterVisitor);
        }

        if (!TYPE_OBJECT.equals(returnType) && (returnExpressionType != null)) {
            populateBindingsWithTypeArgumentVisitor.init(contextualTypeBounds, bindings, typeBounds, returnType);
            returnExpressionType.accept(populateBindingsWithTypeArgumentVisitor);
        }

        if (parameterTypes != null) {
            populateBindingsWithTypeArgumentVisitor.setFixedNames(fixedNames);

            if (parameterTypes.isList() && parameters.isList()) {
                Iterator<Type> parameterTypesIterator = parameterTypes.iterator();
                Iterator<Expression> parametersIterator = parameters.iterator();

                while (parametersIterator.hasNext()) {
                    populateBindingsWithTypeArgument(bindings, typeBounds, parameterTypesIterator.next(), parametersIterator.next());
                }
            } else {
                populateBindingsWithTypeArgument(bindings, typeBounds, parameterTypes.getFirst(), parameters.getFirst());
            }

            populateBindingsWithTypeArgumentVisitor.setFixedNames(Collections.emptySet());
        }

        boolean bindingsContainsNull = bindings.containsValue(null);

        if (bindingsContainsNull) {
            if (eraseTypeArguments(expression, typeParameters, typeArguments)) {
                for (Map.Entry<String, TypeArgument> entry : bindings.entrySet()) {
                    entry.setValue(null);
                }
            } else {
                for (Map.Entry<String, TypeArgument> entry : bindings.entrySet()) {
                    if (entry.getValue() == null) {
                        BaseType baseType = typeBounds.get(entry.getKey());

                        if (baseType == null) {
                            entry.setValue(WildcardTypeArgument.WILDCARD_TYPE_ARGUMENT);
                        } else {
                            bindTypesToTypesVisitor.setBindings(bindings);
                            bindTypesToTypesVisitor.init();
                            baseType.accept(bindTypesToTypesVisitor);
                            baseType = bindTypesToTypesVisitor.getType();

                            baseTypeToTypeArgumentVisitor.init();
                            baseType.accept(baseTypeToTypeArgumentVisitor);
                            entry.setValue(baseTypeToTypeArgumentVisitor.getTypeArgument());
                        }
                    }
                }
            }
        }

        return bindingsContainsNull;
    }

    /** @return true for a type argument without wildcard nor type variable ('Map.Entry<K, V>' of 'List<Map.Entry<K, V>>') */
    protected static boolean isFixed(TypeArgument typeArgument) {
        return (typeArgument != null) && (typeArgument.isObjectTypeArgument() || typeArgument.isInnerObjectTypeArgument());
    }

    protected boolean eraseTypeArguments(Expression expression, BaseTypeParameter typeParameters, BaseTypeArgument typeArguments) {
        if ((typeParameters != null) && (typeArguments == null) && (expression != null)) {
            if (expression.isCastExpression()) {
                expression = expression.getExpression();
            }
            if (expression.isFieldReferenceExpression() || expression.isMethodInvocationExpression() || expression.isLocalVariableReferenceExpression()) {
                return true;
            }
        }

        return false;
    }

    protected void populateBindingsWithTypeArgument(Map<String, TypeArgument> bindings, Map<String, BaseType> typeBounds, Type type, Expression expression) {
        Type t = getExpressionType(expression);

        if ((t != null) && (t != TYPE_UNDEFINED_OBJECT)) {
            populateBindingsWithTypeArgumentVisitor.init(contextualTypeBounds, bindings, typeBounds, t);
            type.accept(populateBindingsWithTypeArgumentVisitor);
        }
    }

    protected BaseType bind(Map<String, TypeArgument> bindings, BaseType parameterTypes) {
        if ((parameterTypes != null) && !bindings.isEmpty()) {
            bindTypesToTypesVisitor.setBindings(bindings);
            bindTypesToTypesVisitor.init();
            parameterTypes.accept(bindTypesToTypesVisitor);
            parameterTypes = bindTypesToTypesVisitor.getType();
        }

        return parameterTypes;
    }

    protected Type getExpressionType(Expression expression) {
        if (expression.isMethodInvocationExpression()) {
            return getExpressionType((ClassFileMethodInvocationExpression)expression);
        } else if (expression.isNewExpression()) {
            return getExpressionType((ClassFileNewExpression)expression);
        }

        return expression.getType();
    }

    protected Type getExpressionType(ClassFileMethodInvocationExpression mie) {
        Type t = mie.getType();

        searchInTypeArgumentVisitor.init();
        t.accept(searchInTypeArgumentVisitor);

        if (!searchInTypeArgumentVisitor.containsGeneric()) {
            return t;
        }

        if (mie.getTypeParameters() != null) {
            return null;
        }

        if (staticMethod || !mie.getInternalTypeName().equals(internalTypeName)) {
            TypeMaker.TypeTypes typeTypes = typeMaker.makeTypeTypes(mie.getInternalTypeName());

            if ((typeTypes != null) && (typeTypes.typeParameters != null)) {
                return null;
            }
        }

        return t;
    }

    protected Type getExpressionType(ClassFileNewExpression ne) {
        ObjectType ot = ne.getObjectType();

        if (staticMethod || !ot.getInternalName().equals(internalTypeName)) {
            TypeMaker.TypeTypes typeTypes = typeMaker.makeTypeTypes(ot.getInternalName());

            if ((typeTypes != null) && (typeTypes.typeParameters != null)) {
                return null;
            }
        }

        return ot;
    }

    protected static class RemoveNonWildcardTypeArgumentsVisitor extends AbstractNopExpressionVisitor {
        @Override
        public void visit(MethodInvocationExpression expression) {
            if (expression.getTypeAnnotationReferences() == null) {
                expression.setNonWildcardTypeArguments(null);
            }
        }

        @Override
        public void visit(TernaryOperatorExpression expression) {
            // 'elements instanceof Collection ? Collections2.cast(elements) : newArrayList(elements)'
            expression.getTrueExpression().accept(this);
            expression.getFalseExpression().accept(this);
        }
    }

    // --- ExpressionVisitor --- //
    protected Type type;

    @Override
    public void visit(MethodInvocationExpression expression) {
        ClassFileMethodInvocationExpression mie = (ClassFileMethodInvocationExpression)expression;

        if (! mie.isBound()) {
            BaseType parameterTypes = mie.getParameterTypes();
            BaseExpression parameters = mie.getParameters();
            Expression exp = mie.getExpression();

            if (exp.isMethodInvocationExpression() && !((ClassFileMethodInvocationExpression)exp).isBound() &&
                    (((ClassFileMethodInvocationExpression)exp).getTypeParameters() == null)) {
                // A receiver is a standalone expression (JLS 15.12): its type does not depend on this invocation.
                // Bound first, 'stream()' of 'list.stream().filter(...)' gives 'Stream<String>' to bind 'filter'.
                // Not the generic methods ('FluentIterable.<V>of(values).transform(...)'): their type arguments
                // are still inferred with the expected type
                Type t = this.type;
                this.type = TYPE_OBJECT;
                exp.accept(this);
                this.type = t;
            }

            Type expressionType = exp.getType();
            // To bind again the returned type of a generic method with the bound arguments
            BaseTypeParameter rebindTypeParameters = null;
            BaseTypeArgument rebindTypeArguments = null;
            BaseType unboundParameterTypes = null;
            Type unboundType = null;

            if (staticMethod || (mie.getTypeParameters() != null) || !mie.getInternalTypeName().equals(internalTypeName)) {
                TypeMaker.TypeTypes typeTypes = typeMaker.makeTypeTypes(mie.getInternalTypeName());

                if (typeTypes != null) {
                    BaseTypeParameter typeParameters = typeTypes.typeParameters;
                    BaseTypeParameter methodTypeParameters = mie.getTypeParameters();
                    BaseTypeArgument typeArguments;

                    if (exp.isSuperExpression()) {
                        typeTypes = typeMaker.makeTypeTypes(internalTypeName);
                        typeArguments = (typeTypes == null || typeTypes.superType == null) ? null : typeTypes.superType.getTypeArguments();
                    } else if (exp.isMethodInvocationExpression()) {
                        Type t = getExpressionType((ClassFileMethodInvocationExpression) exp);
                        if ((t != null) && t.isObjectType()) {
                            typeArguments = ((ObjectType)t).getTypeArguments();
                        } else {
                            typeArguments = null;
                        }
                    } else if (expressionType.isGenericType()) {
                        BaseType typeBound = contextualTypeBounds.get(expressionType.getName());

                        if (typeBound != null) {
                            getTypeArgumentVisitor.init();
                            typeBound.accept(getTypeArgumentVisitor);
                            typeArguments = getTypeArgumentVisitor.getTypeArguments();
                        } else {
                            typeArguments = null;
                        }
                    } else {
                        typeArguments = ((ObjectType)expressionType).getTypeArguments();

                        if ((typeArguments == null) && (typeParameters != null) && isOuterThisReference(exp)) {
                            // 'StandardTable.this.column((C)key)': the outer instance has the type variables of its type
                            typeArguments = toTypeArguments(typeParameters);
                        }
                    }

                    Type t = mie.getType();

                    if (type.isObjectType() && t.isObjectType()) {
                        ObjectType objectType = (ObjectType) type;
                        ObjectType mieTypeObjectType = (ObjectType) t;
                        t = typeMaker.searchSuperParameterizedType(objectType, mieTypeObjectType);
                        if (t == null) {
                            t = mie.getType();
                        }
                    }

                    Map<String, TypeArgument> bindings = new HashMap<>();
                    boolean partialBinding = populateBindings(bindings, exp, typeParameters, typeArguments, methodTypeParameters, type, t, parameterTypes, parameters);

                    rebindTypeParameters = typeParameters;
                    rebindTypeArguments = typeArguments;
                    unboundParameterTypes = parameterTypes;
                    unboundType = mie.getType();

                    Type erasedReturnedType = getRawMemberReturnedType(exp, typeParameters, mie.getType());

                    mie.setParameterTypes(parameterTypes = bind(bindings, parameterTypes));
                    mie.setType((erasedReturnedType != null) ? erasedReturnedType : (Type) bind(bindings, mie.getType()));

                    if ((methodTypeParameters != null) && (parameters == null) && !partialBinding && isTypeBoundViolated(methodTypeParameters, bindings)) {
                        // '<C extends Comparable> Ordering<C> natural()' with the target type 'Ordering<E>': 'E' is not
                        // a 'Comparable', 'C' must not be inferred from the target type
                        mie.setRawCast(true);
                    } else if ((methodTypeParameters != null) && (parameters == null) && isReceiverTypeBoundViolated(exp, type)) {
                        // '(Comparator)Ordering.natural().reverse()' with the target type 'Comparator<? super E>': 'C' of
                        // 'natural()' is not inferred from the target type, 'E' is not a 'Comparable'
                        mie.setRawCast(true);
                    }

                    if ((methodTypeParameters != null) && !partialBinding && !containsInvocationOnWildcardType(parameters)) {
                        // 'Iterables.getOnlyElement(table.cellSet())' with 'Table<? extends R, ? extends C, ? extends V> table':
                        // the argument is typed by captures, an explicit type argument would not fit it
                        bindTypeParametersToNonWildcardTypeArgumentsVisitor.init(bindings);
                        methodTypeParameters.accept(bindTypeParametersToNonWildcardTypeArgumentsVisitor);
                        mie.setNonWildcardTypeArguments(bindTypeParametersToNonWildcardTypeArgumentsVisitor.getTypeArgument());
                    }

                    if (expressionType.isObjectType()) {
                        ObjectType expressionObjectType = (ObjectType) expressionType;

                        if (bindings.isEmpty() || partialBinding) {
                            expressionType = expressionObjectType.createType(null);
                        } else {
                            if (exp.isObjectTypeReferenceExpression() || (typeParameters == null)) {
                                expressionType = expressionObjectType.createType(null);
                            } else if (typeParameters.isList()) {
                                TypeArguments tas = new TypeArguments(typeParameters.size());
                                for (TypeParameter typeParameter : typeParameters) {
                                    tas.add(bindings.get(typeParameter.getIdentifier()));
                                }
                                expressionType = expressionObjectType.createType(tas);
                            } else {
                                expressionType = expressionObjectType.createType(bindings.get(typeParameters.getFirst().getIdentifier()));
                            }
                        }
                    } else if (expressionType.isGenericType()) {
                        if (bindings.isEmpty() || partialBinding) {
                            expressionType = ObjectType.TYPE_OBJECT;
                        } else {
                            TypeArgument typeArgument = bindings.get(expressionType.getName());
                            if (typeArgument == null) {
                                expressionType = ObjectType.TYPE_OBJECT;
                            } else {
                                typeArgumentToTypeVisitor.init();
                                typeArgument.accept(typeArgumentToTypeVisitor);
                                expressionType = typeArgumentToTypeVisitor.getType();
                            }
                        }
                    }
                }
            }

            this.type = expressionType;
            exp.accept(this);

            if (exp.isMethodInvocationExpression() && (((MethodInvocationExpression)exp).getTypeAnnotationReferences() == null) && isInferredFromArguments((ClassFileMethodInvocationExpression)exp)) {
                // '.map(String::trim)', not '.<String>map(String::trim)': a receiver is inferred from its arguments
                ((ClassFileMethodInvocationExpression)exp).setNonWildcardTypeArguments(null);
            }

            bindParameters(parameterTypes, parameters);

            if ((unboundType != null) && (mie.getTypeParameters() != null) && (parameters != null) &&
                    (TYPE_OBJECT.equals(mie.getType()) || containsGeneric(mie.getType())) && containsGeneric(unboundType)) {
                // 'stream.collect(Collectors.partitioningBy(...))': 'R' of 'collect' is known once the argument is bound
                // with the parameter type 'Collector<? super T, A, R>'
                Map<String, TypeArgument> bindings = new HashMap<>();

                if (!populateBindings(bindings, exp, rebindTypeParameters, rebindTypeArguments, mie.getTypeParameters(), TYPE_OBJECT, null, unboundParameterTypes, parameters)) {
                    Type rebound = (Type)bind(bindings, unboundType);

                    if (!containsGeneric(rebound) && !TYPE_OBJECT.equals(rebound)) {
                        mie.setType(rebound);
                    }
                }
            }

            mie.setBound(true);
        }
    }

    /** @return true if a type parameter is bound to a type variable whose bound does not satisfy the type parameter bound */
    protected boolean isTypeBoundViolated(BaseTypeParameter typeParameters, Map<String, TypeArgument> bindings) {
        for (TypeParameter typeParameter : (typeParameters.isList() ? (TypeParameters)typeParameters : java.util.Collections.singletonList((TypeParameter)typeParameters.getFirst()))) {
            if (!(typeParameter instanceof TypeParameterWithTypeBounds)) {
                continue;
            }

            BaseType typeBounds = ((TypeParameterWithTypeBounds)typeParameter).getTypeBounds();
            TypeArgument typeArgument = bindings.get(typeParameter.getIdentifier());

            if (typeBounds.isList() || !typeBounds.isObjectType() || !(typeArgument instanceof GenericType)) {
                continue;
            }

            GenericType genericType = (GenericType)typeArgument;
            BaseType genericTypeBounds = contextualTypeBounds.get(genericType.getName());
            ObjectType bound = (ObjectType)typeBounds;

            if ((genericType.getDimension() == 0) && ((genericTypeBounds == null) || genericTypeBounds.isList() || !genericTypeBounds.isObjectType() ||
                    !typeMaker.isRawTypeAssignable(bound, (ObjectType)genericTypeBounds))) {
                return true;
            }
        }

        return false;
    }

    /**
     * @return true if the receiver is a generic method invocation typed by one of its own type parameters ('Ordering&lt;C&gt;'
     * of '&lt;C extends Comparable&gt; natural()') and a type variable of the target type does not fit the bound of it
     */
    protected boolean isReceiverTypeBoundViolated(Expression receiver, Type type) {
        if (!(receiver instanceof ClassFileMethodInvocationExpression) || !type.isObjectType()) {
            return false;
        }

        ClassFileMethodInvocationExpression receiverMie = (ClassFileMethodInvocationExpression)receiver;
        BaseTypeParameter receiverTypeParameters = receiverMie.getTypeParameters();
        Type receiverType = receiverMie.getType();

        if ((receiverTypeParameters == null) || (receiverMie.getParameters() != null) || !receiverType.isObjectType()) {
            return false;
        }

        BaseTypeArgument receiverTypeArguments = ((ObjectType)receiverType).getTypeArguments();
        BaseTypeArgument targetTypeArguments = ((ObjectType)type).getTypeArguments();

        if ((receiverTypeArguments == null) || receiverTypeArguments.isTypeArgumentList() || (targetTypeArguments == null) || targetTypeArguments.isTypeArgumentList()) {
            return false;
        }

        TypeArgument receiverTypeArgument = receiverTypeArguments.getTypeArgumentFirst();

        if (!(receiverTypeArgument instanceof GenericType)) {
            return false;
        }

        String name = ((GenericType)receiverTypeArgument).getName();

        for (TypeParameter typeParameter : (receiverTypeParameters.isList() ? (TypeParameters)receiverTypeParameters : Collections.singletonList((TypeParameter)receiverTypeParameters.getFirst()))) {
            if (name.equals(typeParameter.getIdentifier()) && (typeParameter instanceof TypeParameterWithTypeBounds)) {
                BaseType bound = ((TypeParameterWithTypeBounds)typeParameter).getTypeBounds();
                TypeArgument targetTypeArgument = targetTypeArguments.getTypeArgumentFirst();

                if (targetTypeArgument instanceof WildcardSuperTypeArgument) {
                    targetTypeArgument = ((WildcardSuperTypeArgument)targetTypeArgument).getType();
                } else if (targetTypeArgument instanceof WildcardExtendsTypeArgument) {
                    targetTypeArgument = ((WildcardExtendsTypeArgument)targetTypeArgument).getType();
                }
                if (bound.isList() || !bound.isObjectType() || !(targetTypeArgument instanceof GenericType) || (((GenericType)targetTypeArgument).getDimension() != 0)) {
                    return false;
                }

                BaseType targetBound = contextualTypeBounds.get(((GenericType)targetTypeArgument).getName());

                return (targetBound == null) || targetBound.isList() || !targetBound.isObjectType() ||
                       !typeMaker.isRawTypeAssignable((ObjectType)bound, (ObjectType)targetBound);
            }
        }

        return false;
    }

    /**
     * 'entries[i].getKey()' with 'Map.Entry[] entries' in '&lt;K, V&gt; copyOf(...)' (no local variable table): the member
     * of a raw type is erased, the type parameter 'K' of 'Map.Entry' is not the type variable 'K' of the context.
     *
     * @return the erasure of the returned type, or null
     */
    protected Type getRawMemberReturnedType(Expression receiver, BaseTypeParameter typeParameters, Type returnedType) {
        if ((receiver == null) || (typeParameters == null) || !returnedType.isGenericType() || (returnedType.getDimension() != 0) ||
                !(receiver.isLocalVariableReferenceExpression() || receiver.isArrayExpression()) ||
                !receiver.getType().isObjectType() || (((ObjectType)receiver.getType()).getTypeArguments() != null) ||
                !contextualBindings.containsKey(returnedType.getName())) {
            return null;
        }

        for (TypeParameter typeParameter : (typeParameters.isList() ? (TypeParameters)typeParameters : Collections.singletonList((TypeParameter)typeParameters.getFirst()))) {
            if (typeParameter.getIdentifier().equals(returnedType.getName())) {
                BaseType bound = (typeParameter instanceof TypeParameterWithTypeBounds) ? ((TypeParameterWithTypeBounds)typeParameter).getTypeBounds() : null;
                return ((bound != null) && !bound.isList() && bound.isObjectType()) ? ((ObjectType)bound).createType(null) : TYPE_OBJECT;
            }
        }

        return null;
    }

    protected static boolean isOuterThisReference(Expression expression) {
        return expression.isFieldReferenceExpression() && expression.getName().startsWith("this$") && expression.getType().isObjectType();
    }

    protected static BaseTypeArgument toTypeArguments(BaseTypeParameter typeParameters) {
        if (typeParameters.isList()) {
            TypeArguments typeArguments = new TypeArguments(typeParameters.size());
            for (TypeParameter typeParameter : (TypeParameters)typeParameters) {
                typeArguments.add(new GenericType(typeParameter.getIdentifier()));
            }
            return typeArguments;
        }
        return new GenericType(typeParameters.getFirst().getIdentifier());
    }

    protected static boolean containsInvocationOnWildcardType(BaseExpression parameters) {
        if (parameters != null) {
            for (Expression parameter : parameters) {
                if (LoopStatementMaker.isInvocationOnWildcardType(parameter)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** @return true if each type parameter of the generic method is in the type of a parameter */
    protected boolean isInferredFromArguments(ClassFileMethodInvocationExpression mie) {
        if ((mie.getNonWildcardTypeArguments() == null) || (mie.getTypeParameters() == null) || (mie.getParameters() == null)) {
            return false;
        }

        for (Expression parameter : mie.getParameters()) {
            if (parameter instanceof LambdaIdentifiersExpression) {
                // 'Comparator.<MethodDescriptor, String>comparing(descriptor -> ...).thenComparing(...)': the type of
                // the parameter of an implicitly typed lambda is not inferred from its body
                return false;
            }
        }

        TypeMaker.MethodTypes methodTypes = typeMaker.makeMethodTypes(mie.getInternalTypeName(), mie.getName(), mie.getDescriptor());

        if ((methodTypes == null) || (methodTypes.parameterTypes == null)) {
            return false;
        }

        HashSet<String> names = new HashSet<>();
        methodTypes.parameterTypes.accept(new org.jd.core.v1.model.javasyntax.AbstractJavaSyntaxVisitor() {
            @Override
            public void visit(GenericType type) {
                names.add(type.getName());
            }
        });

        for (TypeParameter typeParameter : (mie.getTypeParameters().isList() ? (TypeParameters)mie.getTypeParameters() : java.util.Collections.singletonList((TypeParameter)mie.getTypeParameters().getFirst()))) {
            if (!names.contains(typeParameter.getIdentifier())) {
                return false;
            }
        }

        return true;
    }

    protected boolean containsGeneric(Type type) {
        searchInTypeArgumentVisitor.init();
        type.accept(searchInTypeArgumentVisitor);
        return searchInTypeArgumentVisitor.containsGeneric();
    }

    @Override
    public void visit(LocalVariableReferenceExpression expression) {
        AbstractLocalVariable localVariable = ((ClassFileLocalVariableReferenceExpression) expression).getLocalVariable();
        if (localVariable.getFromOffset() > 0) {
            // Do not update parameter
            localVariable.typeOnLeft(contextualTypeBounds, checkTypeArguments(type, localVariable));
        }
    }

    @Override
    public void visit(NewExpression expression) {
        ClassFileNewExpression ne = (ClassFileNewExpression)expression;

        if (! ne.isBound()) {
            BaseType parameterTypes = ne.getParameterTypes();
            BaseExpression parameters = ne.getParameters();
            ObjectType neObjectType = ne.getObjectType();

            if (staticMethod || !neObjectType.getInternalName().equals(internalTypeName)) {
                TypeMaker.TypeTypes typeTypes = typeMaker.makeTypeTypes(neObjectType.getInternalName());

                if (typeTypes != null) {
                    BaseTypeParameter typeParameters = typeTypes.typeParameters;
                    BaseTypeArgument typeArguments = neObjectType.getTypeArguments();

                    if ((typeParameters != null) && (typeArguments == null)) {
                        if (typeParameters.isList()) {
                            TypeArguments tas = new TypeArguments(typeParameters.size());
                            for (TypeParameter typeParameter : typeParameters) {
                                tas.add(new GenericType(typeParameter.getIdentifier()));
                            }
                            neObjectType = neObjectType.createType(tas);
                        } else {
                            neObjectType = neObjectType.createType(new GenericType(typeParameters.getFirst().getIdentifier()));
                        }
                    }

                    Type t = neObjectType;

                    if (type.isObjectType()) {
                        ObjectType objectType = (ObjectType)type;
                        t = typeMaker.searchSuperParameterizedType(objectType, neObjectType);
                        if (t == null) {
                            t = neObjectType;
                        }
                    }

                    Map<String, TypeArgument> bindings = new HashMap<>();
                    boolean partialBinding = populateBindings(bindings, null, typeParameters, typeArguments, null, type, t, parameterTypes, parameters);

                    ne.setParameterTypes(parameterTypes = bind(bindings, parameterTypes));

                    // Replace wildcards
                    for (Map.Entry<String, TypeArgument> entry : bindings.entrySet()) {
                        typeArgumentToTypeVisitor.init();
                        entry.getValue().accept(typeArgumentToTypeVisitor);
                        entry.setValue(typeArgumentToTypeVisitor.getType());
                    }

                    if (!partialBinding) {
                        ne.setType((ObjectType) bind(bindings, neObjectType));
                    }
                }
            }

            bindParameters(parameterTypes, parameters);

            ne.setBound(true);
        }
    }

    @Override
    public void visit(CastExpression expression) {
        assert TYPE_OBJECT.equals(type) || (type.getDimension() == expression.getType().getDimension()) : "TypeParametersToTypeArgumentsBinder.visit(CastExpression ce) : invalid array type";

        if (type.isObjectType()) {
            ObjectType objectType = (ObjectType)type;

            if ((objectType.getTypeArguments() != null) && !objectType.getTypeArguments().equals(WildcardTypeArgument.WILDCARD_TYPE_ARGUMENT)) {
                assert expression.getType().isObjectType() : "TypeParametersToTypeArgumentsBinder.visit(CastExpression ce) : invalid object type";

                ObjectType expressionObjectType = (ObjectType) expression.getType();

                if (objectType.getInternalName().equals(expressionObjectType.getInternalName())) {
                    Type expressionExpressionType = expression.getExpression().getType();

                    if (expressionExpressionType.isObjectType()) {
                        ObjectType expressionExpressionObjectType = (ObjectType)expressionExpressionType;

                        if (expressionExpressionObjectType.getTypeArguments() == null) {
                            expression.setType(objectType);
                        } else if (objectType.getTypeArguments().isTypeArgumentAssignableFrom(contextualTypeBounds, expressionExpressionObjectType.getTypeArguments())) {
                            expression.setType(objectType);
                        }
                    } else if (expressionExpressionType.isGenericType()) {
                        expression.setType(objectType);
                    }
                }
            }
        }

        type = expression.getType();
        expression.getExpression().accept(this);
    }

    @Override
    public void visit(TernaryOperatorExpression expression) {
        Type t = type;

        expression.setType(t);
        expression.getTrueExpression().accept(this);
        type = t;
        expression.getFalseExpression().accept(this);

        if (t.isGenericType() && !isInScope((GenericType)t)) {
            // 'range.has() ? range.lower() : null': the type of a bound branch, not 'C' of 'lower()'
            Expression e = expression.getTrueExpression().isNullExpression() ? expression.getFalseExpression() : expression.getTrueExpression();

            if (e.getType().isGenericType() && isInScope((GenericType)e.getType())) {
                expression.setType(e.getType());
            }
        }
    }

    @Override
    public boolean isInScope(GenericType type) {
        return contextualBindings.containsKey(type.getName());
    }

    @Override
    public void visit(BinaryOperatorExpression expression) {
        if ((expression.getType() == TYPE_STRING) && "+".equals(expression.getOperator())) {
            type = TYPE_OBJECT;
        }

        Type t = type;

        expression.getLeftExpression().accept(this);
        type = t;
        expression.getRightExpression().accept(this);
    }

    /**
     * The values of 'yield' have the type of the switch expression: 'String s = switch (k) { case 0 -> l.get(0); ... }'
     * binds 'E' of 'get', as 'List<String> r = switch (k) { case 1 -> List.of("a"); ... }' binds 'E' of 'of'
     */
    @Override
    public void visit(SwitchExpression expression) {
        Type expectedType = type;

        if (TYPE_OBJECT.equals(expectedType)) {
            expectedType = expression.getType();
        } else {
            expression.setType(expectedType);
        }

        SwitchStatement switchStatement = expression.getSwitchStatement();
        Type yieldType = expectedType;

        type = TYPE_OBJECT;
        switchStatement.getCondition().accept(this);

        switchStatement.accept(new org.jd.core.v1.model.javasyntax.AbstractJavaSyntaxVisitor() {
            @Override
            public void visit(YieldStatement statement) {
                type = yieldType;
                statement.getExpression().accept(Java5TypeParametersToTypeArgumentsBinder.this);
                statement.getExpression().accept(REMOVE_NON_WILDCARD_TYPE_ARGUMENTS_VISITOR);
            }

            // The values of a nested switch expression are bound by its 'yield' of this switch expression
            @Override public void visit(SwitchExpression e) {}
        });

        type = expectedType;
    }
    @Override public void visit(TypePatternExpression expression) {}
    @Override public void visit(RecordPatternExpression expression) {}
    @Override public void visit(ArrayExpression expression) {}
    @Override public void visit(BooleanExpression expression) {}
    @Override public void visit(CommentExpression expression) {}
    @Override public void visit(ConstructorInvocationExpression expression) {}
    @Override public void visit(ConstructorReferenceExpression expression) {}
    @Override public void visit(DoubleConstantExpression expression) {}
    @Override public void visit(EnumConstantReferenceExpression expression) {}
    @Override public void visit(Expressions expression) {}
    @Override public void visit(FieldReferenceExpression expression) {}
    @Override public void visit(FloatConstantExpression expression) {}
    @Override public void visit(IntegerConstantExpression expression) {}
    @Override public void visit(InstanceOfExpression expression) {}
    @Override public void visit(LambdaFormalParametersExpression expression) {}
    @Override public void visit(LambdaIdentifiersExpression expression) {}
    @Override public void visit(LengthExpression expression) {}
    @Override public void visit(LongConstantExpression expression) {}
    @Override public void visit(MethodReferenceExpression expression) {}
    @Override public void visit(NewArray expression) {}
    @Override public void visit(NewInitializedArray expression) {}
    @Override public void visit(NoExpression expression) {}
    @Override public void visit(NullExpression expression) {}
    @Override public void visit(ObjectTypeReferenceExpression expression) {}
    @Override public void visit(ParenthesesExpression expression) {}
    @Override public void visit(PostOperatorExpression expression) {}
    @Override public void visit(PreOperatorExpression expression) {}
    @Override public void visit(StringConstantExpression expression) {}
    @Override public void visit(SuperConstructorInvocationExpression expression) {}
    @Override public void visit(SuperExpression expression) {}
    @Override public void visit(ThisExpression expression) {}
    @Override public void visit(TypeReferenceDotClassExpression expression) {}
}
