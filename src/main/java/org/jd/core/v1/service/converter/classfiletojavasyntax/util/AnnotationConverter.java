/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.util;

import org.jd.core.v1.api.loader.Loader;
import org.jd.core.v1.model.classfile.ClassFile;
import org.jd.core.v1.model.classfile.attribute.*;
import org.jd.core.v1.model.classfile.attribute.ElementValue;
import org.jd.core.v1.model.classfile.attribute.ElementValuePair;
import org.jd.core.v1.model.classfile.constant.*;
import org.jd.core.v1.model.javasyntax.expression.*;
import org.jd.core.v1.model.javasyntax.reference.*;
import org.jd.core.v1.model.javasyntax.type.ObjectType;
import org.jd.core.v1.model.javasyntax.type.PrimitiveType;
import org.jd.core.v1.service.deserializer.classfile.ClassFileDeserializer;

import java.util.HashMap;

public class AnnotationConverter implements ElementValueVisitor {
    protected TypeMaker typeMaker;
    protected org.jd.core.v1.model.javasyntax.reference.ElementValue elementValue = null;

    // Loader of the annotation types: '@Tags({@Tag("a"), @Tag("b")})' is written '@Tag("a") @Tag("b")', or null
    protected Loader repeatableAnnotationLoader;
    protected HashMap<String, String> containerDescriptors = new HashMap<>();

    public AnnotationConverter(TypeMaker typeMaker) {
        this.typeMaker = typeMaker;
    }

    /**
     * Java 8+ repeatable annotations ('repeatableAnnotations' configuration): a container annotation is replaced
     * by its annotations when their type is annotated '@Repeatable(Container.class)'
     */
    public void setRepeatableAnnotationLoader(Loader loader) {
        this.repeatableAnnotationLoader = loader;
    }

    /** Adds the annotation, or the annotations of a container of repeatable annotations */
    @SuppressWarnings("unchecked")
    protected void addConverted(AnnotationReferences list, Annotation annotation) {
        Annotation[] repeated = getRepeatedAnnotations(annotation);

        if (repeated == null) {
            list.add(convert(annotation));
        } else {
            for (Annotation a : repeated) {
                list.add(convert(a));
            }
        }
    }

    /** @return the annotations of '@Tags({@Tag("a"), @Tag("b")})' if 'Tag' is '@Repeatable(Tags.class)', or null */
    protected Annotation[] getRepeatedAnnotations(Annotation container) {
        ElementValuePair[] pairs = container.getElementValuePairs();

        if ((repeatableAnnotationLoader == null) || (pairs == null) || (pairs.length != 1) || !"value".equals(pairs[0].getElementName()) ||
                !(pairs[0].getElementValue() instanceof ElementValueArrayValue)) {
            return null;
        }

        ElementValue[] values = ((ElementValueArrayValue)pairs[0].getElementValue()).getValues();

        if ((values == null) || (values.length < 2)) {
            return null;
        }

        Annotation[] annotations = new Annotation[values.length];

        for (int i = 0; i < values.length; i++) {
            if (!(values[i] instanceof ElementValueAnnotationValue)) {
                return null;
            }
            annotations[i] = ((ElementValueAnnotationValue)values[i]).getAnnotationValue();
            if (!annotations[i].getDescriptor().equals(annotations[0].getDescriptor())) {
                return null;
            }
        }

        return container.getDescriptor().equals(getContainerDescriptor(annotations[0].getDescriptor())) ? annotations : null;
    }

    /** @return the descriptor of 'X' in '@Repeatable(X.class)' of the annotation type, or "" */
    protected String getContainerDescriptor(String descriptor) {
        return containerDescriptors.computeIfAbsent(descriptor, d -> {
            try {
                String internalTypeName = d.substring(1, d.length() - 1);
                ClassFile classFile = new ClassFileDeserializer().loadClassFile(repeatableAnnotationLoader, internalTypeName);

                for (String name : new String[] { "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" }) {
                    Annotations annotations = classFile.getAttribute(name);

                    if (annotations != null) {
                        for (Annotation a : annotations.getAnnotations()) {
                            ElementValuePair[] pairs = a.getElementValuePairs();

                            if ("Ljava/lang/annotation/Repeatable;".equals(a.getDescriptor()) && (pairs != null) && (pairs.length == 1) &&
                                    (pairs[0].getElementValue() instanceof ElementValueClassInfo)) {
                                return ((ElementValueClassInfo)pairs[0].getElementValue()).getClassInfo();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Not loaded: the container is kept
            }
            return "";
        });
    }

    @SuppressWarnings("unchecked")
    public BaseAnnotationReference convert(Annotations visibles, Annotations invisibles) {
        if (visibles == null) {
            if (invisibles == null) {
                return null;
            } else {
                return convert(invisibles);
            }
        } else {
            if (invisibles == null) {
                return convert(visibles);
            } else {
                AnnotationReferences aral = new AnnotationReferences();

                for (Annotation a : visibles.getAnnotations()) {
                    addConverted(aral, a);
                }
                for (Annotation a : invisibles.getAnnotations()) {
                    addConverted(aral, a);
                }

                return aral;
            }
        }
    }

    /**
     * @param declarationAnnotations annotations already printed on the declaration: javac copies an annotation whose
     *                               targets contain both a declaration and 'TYPE_USE' into both attributes
     * @param parameterAnnotations   annotations already printed on the formal parameters, or null
     * @return the annotations of the declared types, or null
     */
    public TypeAnnotationReferences convert(
            AttributeTypeAnnotations visibles, AttributeTypeAnnotations invisibles,
            Annotations[] declarationAnnotations, AttributeParameterAnnotations[] parameterAnnotations) {
        return convert(visibles, invisibles, declarationAnnotations, parameterAnnotations, null);
    }

    /**
     * @param typeParameterClassBounds for each type parameter, true if its signature has a class bound
     *                                 ('T:Ljava/lang/Number;'), false for interface bounds only ('T::Ljava/lang/Comparable;'),
     *                                 or null
     */
    public TypeAnnotationReferences convert(
            AttributeTypeAnnotations visibles, AttributeTypeAnnotations invisibles,
            Annotations[] declarationAnnotations, AttributeParameterAnnotations[] parameterAnnotations,
            boolean[] typeParameterClassBounds) {
        TypeAnnotationReferences references = new TypeAnnotationReferences();

        for (AttributeTypeAnnotations attribute : new AttributeTypeAnnotations[] { visibles, invisibles }) {
            if (attribute != null) {
                for (TypeAnnotation ta : attribute.getTypeAnnotations()) {
                    String descriptor = ta.getAnnotation().getDescriptor();

                    if (ta.getTypePath().isEmpty()) {
                        int targetType = ta.getTargetType();

                        if (((targetType == TypeAnnotationReferences.FIELD) || (targetType == TypeAnnotationReferences.METHOD_RETURN)) && contains(declarationAnnotations, descriptor)) {
                            continue;
                        }
                        if ((targetType == TypeAnnotationReferences.METHOD_FORMAL_PARAMETER) && contains(parameterAnnotations, ta.getTargetIndex(), descriptor)) {
                            continue;
                        }
                    }

                    int targetIndex = ta.getTargetIndex();

                    if ((ta.getTargetType() == TypeAnnotationReferences.CLASS_TYPE_PARAMETER_BOUND) || (ta.getTargetType() == TypeAnnotationReferences.METHOD_TYPE_PARAMETER_BOUND)) {
                        // 'bound_index' 0 is the class bound, even when the type parameter has interface bounds only
                        if ((typeParameterClassBounds == null) || (targetIndex >= typeParameterClassBounds.length)) {
                            continue;
                        }
                        int boundIndex = typeParameterClassBounds[targetIndex] ? ta.getSecondIndex() : ta.getSecondIndex() - 1;
                        targetIndex = targetIndex * 256 + boundIndex;
                    }

                    references.add(ta.getTargetType(), targetIndex, ta.getTypePath(), convert(ta.getAnnotation()));
                }
            }
        }

        return references.isEmpty() ? null : references;
    }

    /**
     * @param signature class or method signature: '<T:Ljava/lang/Object;U::Ljava/lang/Comparable<TU;>;>...'
     * @return for each type parameter, true if it has a class bound; or null
     */
    public static boolean[] getTypeParameterClassBounds(String signature) {
        if ((signature == null) || !signature.startsWith("<")) {
            return null;
        }

        java.util.ArrayList<Boolean> classBounds = new java.util.ArrayList<>();
        int i = 1;

        while (signature.charAt(i) != '>') {
            // Identifier, then ':' class bound (may be empty), then ':' interface bounds
            i = signature.indexOf(':', i);
            classBounds.add(signature.charAt(i + 1) != ':');

            while (signature.charAt(i) == ':') {
                i++;
                if (signature.charAt(i) != ':') {
                    i = skipReferenceTypeSignature(signature, i);
                }
            }
        }

        boolean[] result = new boolean[classBounds.size()];
        for (int j = 0; j < result.length; j++) {
            result[j] = classBounds.get(j);
        }
        return result;
    }

    /** @return the index after 'Lpkg/Name<...>.Inner<...>;', 'TT;' or '[...' starting at 'i' */
    protected static int skipReferenceTypeSignature(String signature, int i) {
        switch (signature.charAt(i)) {
            case 'T':
                return signature.indexOf(';', i) + 1;
            case '[':
                i++;
                return ("BCDFIJSZ".indexOf(signature.charAt(i)) != -1) ? i + 1 : skipReferenceTypeSignature(signature, i);
            default: // 'L'
                int depth = 0;
                for (i++; ; i++) {
                    char c = signature.charAt(i);
                    if (c == '<') {
                        depth++;
                    } else if (c == '>') {
                        depth--;
                    } else if ((c == ';') && (depth == 0)) {
                        return i + 1;
                    }
                }
        }
    }

    protected static boolean contains(Annotations[] annotationsArray, String descriptor) {
        if (annotationsArray != null) {
            for (Annotations annotations : annotationsArray) {
                if (annotations != null) {
                    for (Annotation a : annotations.getAnnotations()) {
                        if (descriptor.equals(a.getDescriptor())) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    protected static boolean contains(AttributeParameterAnnotations[] parameterAnnotations, int index, String descriptor) {
        if (parameterAnnotations != null) {
            for (AttributeParameterAnnotations apa : parameterAnnotations) {
                if (apa != null) {
                    Annotations[] array = apa.getParameterAnnotations();

                    if ((array != null) && (index < array.length) && contains(new Annotations[] { array[index] }, descriptor)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public AnnotationReference convertTypeAnnotation(TypeAnnotation typeAnnotation) {
        return convert(typeAnnotation.getAnnotation());
    }

    @SuppressWarnings("unchecked")
    protected BaseAnnotationReference convert(Annotations annotations) {
        Annotation[] as = annotations.getAnnotations();

        if ((as.length == 1) && (getRepeatedAnnotations(as[0]) == null)) {
            return convert(as[0]);
        } else {
            AnnotationReferences aral = new AnnotationReferences(as.length);

            for (Annotation a : as) {
                addConverted(aral, a);
            }

            return aral;
        }
    }

    @SuppressWarnings("unchecked")
    protected AnnotationReference convert(Annotation annotation) {
        String descriptor = annotation.getDescriptor();

        assert (descriptor != null) && (descriptor.length() > 2) && (descriptor.charAt(0) == 'L') && (descriptor.charAt(descriptor.length()-1) == ';');

        ObjectType ot = typeMaker.makeFromDescriptor(descriptor);
        ElementValuePair[] elementValuePairs = annotation.getElementValuePairs();

        if (elementValuePairs == null) {
            return new AnnotationReference(ot);
        } else if (elementValuePairs.length == 1) {
            ElementValuePair elementValuePair = elementValuePairs[0];
            String elementName = elementValuePair.getElementName();
            ElementValue elementValue = elementValuePair.getElementValue();

            if ("value".equals(elementName)) {
                return new AnnotationReference(ot, convert(elementValue));
            } else {
                return new AnnotationReference(
                        ot,
                        new org.jd.core.v1.model.javasyntax.reference.ElementValuePair(elementName, convert(elementValue)));
            }
        } else {
            ElementValuePairs list = new ElementValuePairs(elementValuePairs.length);

            for (ElementValuePair elementValuePair : elementValuePairs) {
                String elementName = elementValuePair.getElementName();
                ElementValue elementValue = elementValuePair.getElementValue();
                list.add(new org.jd.core.v1.model.javasyntax.reference.ElementValuePair(elementName, convert(elementValue)));
            }

            return new AnnotationReference(ot, list);
        }
    }

    public org.jd.core.v1.model.javasyntax.reference.ElementValue convert(ElementValue ev) {
        ev.accept(this);
        return elementValue;
    }

    // --- ElementValueVisitor --- //
    @Override
    public void visit(ElementValuePrimitiveType elementValuePrimitiveType) {
        switch (elementValuePrimitiveType.getType()) {
            case 'B':
                elementValue = new ExpressionElementValue(new IntegerConstantExpression(PrimitiveType.TYPE_BYTE, elementValuePrimitiveType.<ConstantInteger>getConstValue().getValue()));
                break;
            case 'D':
                elementValue = new ExpressionElementValue(new DoubleConstantExpression(elementValuePrimitiveType.<ConstantDouble>getConstValue().getValue()));
                break;
            case 'F':
                elementValue = new ExpressionElementValue(new FloatConstantExpression(elementValuePrimitiveType.<ConstantFloat>getConstValue().getValue()));
                break;
            case 'I':
                elementValue = new ExpressionElementValue(new IntegerConstantExpression(PrimitiveType.TYPE_INT, elementValuePrimitiveType.<ConstantInteger>getConstValue().getValue()));
                break;
            case 'J':
                elementValue = new ExpressionElementValue(new LongConstantExpression(elementValuePrimitiveType.<ConstantLong>getConstValue().getValue()));
                break;
            case 'S':
                elementValue = new ExpressionElementValue(new IntegerConstantExpression(PrimitiveType.TYPE_SHORT, elementValuePrimitiveType.<ConstantInteger>getConstValue().getValue()));
                break;
            case 'Z':
                elementValue = new ExpressionElementValue(new IntegerConstantExpression(PrimitiveType.TYPE_BOOLEAN, elementValuePrimitiveType.<ConstantInteger>getConstValue().getValue()));
                break;
            case 'C':
                elementValue = new ExpressionElementValue(new IntegerConstantExpression(PrimitiveType.TYPE_CHAR, elementValuePrimitiveType.<ConstantInteger>getConstValue().getValue()));
                break;
            case 's':
                elementValue = new ExpressionElementValue(new StringConstantExpression(elementValuePrimitiveType.<ConstantUtf8>getConstValue().getValue()));
                break;
        }
    }

    @Override
    public void visit(ElementValueClassInfo elementValueClassInfo) {
        String classInfo = elementValueClassInfo.getClassInfo();
        ObjectType ot = typeMaker.makeFromDescriptor(classInfo);
        elementValue = new ExpressionElementValue(new TypeReferenceDotClassExpression(ot));
    }

    @Override
    public void visit(ElementValueAnnotationValue elementValueAnnotationValue) {
        Annotation annotationValue = elementValueAnnotationValue.getAnnotationValue();
        AnnotationReference annotationReference = convert(annotationValue);
        elementValue = new AnnotationElementValue(annotationReference);
    }

    @Override
    public void visit(ElementValueEnumConstValue elementValueEnumConstValue) {
        String descriptor = elementValueEnumConstValue.getDescriptor();

        assert (descriptor != null) && (descriptor.length() > 2) && (descriptor.charAt(0) == 'L') && (descriptor.charAt(descriptor.length()-1) == ';') : "AnnotationConverter.visit(elementValueEnumConstValue)";

        ObjectType ot = typeMaker.makeFromDescriptor(descriptor);
        String constName = elementValueEnumConstValue.getConstName();
        String internalTypeName = descriptor.substring(1, descriptor.length()-1);
        elementValue = new ExpressionElementValue(new FieldReferenceExpression(ot, new ObjectTypeReferenceExpression(ot), internalTypeName, constName, descriptor));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void visit(ElementValueArrayValue elementValueArrayValue) {
        ElementValue[] values = elementValueArrayValue.getValues();

        if (values == null) {
            elementValue = new ElementValueArrayInitializerElementValue();
        } else if (values.length == 1) {
            values[0].accept(this);
            elementValue = new ElementValueArrayInitializerElementValue(elementValue);
        } else {
            ElementValues list = new ElementValues(values.length);

            for (ElementValue value : values) {
                value.accept(this);
                list.add(elementValue);
            }

            elementValue = new ElementValueArrayInitializerElementValue(list);
        }
    }
}
