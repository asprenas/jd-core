/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.util;

import org.jd.core.v1.model.classfile.ClassFile;
import org.jd.core.v1.model.classfile.Field;
import org.jd.core.v1.model.classfile.Method;
import org.jd.core.v1.model.classfile.attribute.*;
import org.jd.core.v1.model.javasyntax.declaration.BaseFormalParameter;
import org.jd.core.v1.model.javasyntax.declaration.FormalParameters;
import org.jd.core.v1.model.javasyntax.reference.BaseAnnotationReference;
import org.jd.core.v1.model.javasyntax.reference.TypeAnnotationReferences;
import org.jd.core.v1.model.javasyntax.statement.Statements;
import org.jd.core.v1.model.javasyntax.type.*;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.ClassFileConstructorOrMethodDeclaration;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.ClassFileFormalParameter;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.localvariable.*;
import org.jd.core.v1.service.converter.classfiletojavasyntax.visitor.*;
import org.jd.core.v1.util.DefaultList;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.jd.core.v1.model.classfile.Constants.ACC_ENUM;
import static org.jd.core.v1.model.classfile.Constants.ACC_STATIC;
import static org.jd.core.v1.model.javasyntax.declaration.MethodDeclaration.*;

public class LocalVariableMaker {
    protected LocalVariableSet localVariableSet = new LocalVariableSet();
    protected HashSet<String> names = new HashSet<>();
    protected HashSet<String> blackListNames = new HashSet<>();
    protected Frame currentFrame = new RootFrame();
    protected AbstractLocalVariable[] localVariableCache;
    /** The other 'LocalVariableTable' ranges of a variable: (index, start offset) -> variable of the first range */
    protected HashMap<Long, AbstractLocalVariable> otherRanges = new HashMap<>();

    protected TypeMaker typeMaker;
    protected Map<String, BaseType> typeBounds;
    protected FormalParameters formalParameters;

    protected PopulateBlackListNamesVisitor populateBlackListNamesVisitor = new PopulateBlackListNamesVisitor(blackListNames);
    protected SearchInTypeArgumentVisitor searchInTypeArgumentVisitor = new SearchInTypeArgumentVisitor();
    protected CreateParameterVisitor createParameterVisitor;
    protected CreateLocalVariableVisitor createLocalVariableVisitor;

    @SuppressWarnings("unchecked")
    public TypeMaker getTypeMaker() {
        return typeMaker;
    }

    public LocalVariableMaker(TypeMaker typeMaker, ClassFileConstructorOrMethodDeclaration comd, boolean constructor) {
        ClassFile classFile = comd.getClassFile();
        Method method = comd.getMethod();
        BaseType parameterTypes = comd.getParameterTypes();

        this.typeMaker = typeMaker;
        this.typeBounds = comd.getTypeBounds();
        this.createParameterVisitor = new CreateParameterVisitor(typeMaker);
        this.createLocalVariableVisitor = new CreateLocalVariableVisitor(typeMaker);

        // Initialize local black list variable names
        if (classFile.getFields() != null) {
            for (Field field : classFile.getFields()) {
                String descriptor = field.getDescriptor();

                if (descriptor.charAt(descriptor.length() - 1) == ';') {
                    typeMaker.makeFromDescriptor(descriptor).accept(populateBlackListNamesVisitor);
                }
            }
        }

        typeMaker.makeFromInternalTypeName(classFile.getInternalTypeName()).accept(populateBlackListNamesVisitor);

        if (classFile.getSuperTypeName() != null) {
            typeMaker.makeFromInternalTypeName(classFile.getSuperTypeName()).accept(populateBlackListNamesVisitor);
        }

        if (classFile.getInterfaceTypeNames() != null) {
            for (String interfaceTypeName : classFile.getInterfaceTypeNames()) {
                typeMaker.makeFromInternalTypeName(interfaceTypeName).accept(populateBlackListNamesVisitor);
            }
        }

        if (parameterTypes != null) {
            if (parameterTypes.isList()) {
                for (Type type : parameterTypes) {
                    type.accept(populateBlackListNamesVisitor);
                }
            } else {
                parameterTypes.getFirst().accept(populateBlackListNamesVisitor);
            }
        }

        // Initialize local variables from 'LocalVariableTable' and 'LocalVariableTypeTable' attributes
        initLocalVariablesFromAttributes(method);

        // Initialize local variables from access flags & signature
        int firstVariableIndex = 0;

        if ((method.getAccessFlags() & FLAG_STATIC) == 0) {
            if (localVariableSet.root(0) == null) {
                // Local variable missing
                localVariableSet.add(0, new ObjectLocalVariable(typeMaker, 0, 0, typeMaker.makeFromInternalTypeName(classFile.getInternalTypeName()), "this"));
            }
            firstVariableIndex = 1;
        }

        if (constructor) {
            if (classFile.isEnum()) {
                if (localVariableSet.root(1) == null) {
                    // Local variable missing
                    localVariableSet.add(1, new ObjectLocalVariable(typeMaker, 1, 0, ObjectType.TYPE_STRING, "this$enum$name"));
                }
                if (localVariableSet.root(2) == null) {
                    // Local variable missing
                    localVariableSet.add(2, new PrimitiveLocalVariable(2, 0, PrimitiveType.TYPE_INT, "this$enum$index"));
                }
            } else if ((classFile.getOuterClassFile() != null) && !classFile.isStatic()) {
                if (localVariableSet.root(1) == null) {
                    // Local variable missing
                    localVariableSet.add(1, new ObjectLocalVariable(typeMaker, 1, 0, typeMaker.makeFromInternalTypeName(classFile.getOuterClassFile().getInternalTypeName()), "this$0"));
                }
            }
        }

        if (parameterTypes != null) {
            int lastParameterIndex = parameterTypes.size() - 1;
            boolean varargs = ((method.getAccessFlags() & FLAG_VARARGS) != 0);

            initLocalVariablesFromParameterTypes(classFile, parameterTypes, varargs, firstVariableIndex, lastParameterIndex);

            // Create list of parameterTypes
            formalParameters = new FormalParameters();

            AttributeParameterAnnotations rvpa = method.getAttribute("RuntimeVisibleParameterAnnotations");
            AttributeParameterAnnotations ripa = method.getAttribute("RuntimeInvisibleParameterAnnotations");

            if ((rvpa == null) && (ripa == null)) {
                for (int parameterIndex=0, variableIndex=firstVariableIndex; parameterIndex<=lastParameterIndex; parameterIndex++, variableIndex++) {
                    AbstractLocalVariable lv = localVariableSet.root(variableIndex);

                    formalParameters.add(new ClassFileFormalParameter(lv, varargs && (parameterIndex==lastParameterIndex)));

                    if (PrimitiveType.TYPE_LONG.equals(lv.getType()) || PrimitiveType.TYPE_DOUBLE.equals(lv.getType())) {
                        variableIndex++;
                    }
                }
            } else {
                Annotations[] visiblesArray = (rvpa == null) ? null : rvpa.getParameterAnnotations();
                Annotations[] invisiblesArray = (ripa == null) ? null : ripa.getParameterAnnotations();
                // The converter of the declarations: the same configuration ('repeatableAnnotations')
                AnnotationConverter annotationConverter = (comd.getBodyDeclaration().getAnnotationConverter() == null) ?
                    new AnnotationConverter(typeMaker) : comd.getBodyDeclaration().getAnnotationConverter();

                for (int parameterIndex=0, variableIndex=firstVariableIndex; parameterIndex<=lastParameterIndex; parameterIndex++, variableIndex++) {
                    AbstractLocalVariable lv = localVariableSet.root(variableIndex);

                    Annotations visibles = ((visiblesArray == null) || (visiblesArray.length <= parameterIndex)) ? null : visiblesArray[parameterIndex];
                    Annotations invisibles = ((invisiblesArray == null) || (invisiblesArray.length <= parameterIndex)) ? null : invisiblesArray[parameterIndex];
                    BaseAnnotationReference annotationReferences = annotationConverter.convert(visibles, invisibles);

                    formalParameters.add(new ClassFileFormalParameter(annotationReferences, lv, varargs && (parameterIndex==lastParameterIndex)));

                    if (PrimitiveType.TYPE_LONG.equals(lv.getType()) || PrimitiveType.TYPE_DOUBLE.equals(lv.getType())) {
                        variableIndex++;
                    }
                }
            }
        }

        // Initialize root frame and cache
        localVariableCache = localVariableSet.initialize(currentFrame);
    }

    /** 'localvar_target' type annotations: a range of the annotation is the LocalVariableTable range of the variable */
    protected void initTypeAnnotations(AttributeCode code) {
        AnnotationConverter annotationConverter = null;
        // 'catch_target': handler offsets, a multi-catch has one exception table entry by type
        HashSet<String> catchAnnotations = new HashSet<>();

        for (String name : new String[] { "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations" }) {
            AttributeTypeAnnotations attribute = code.getAttribute(name);

            if (attribute != null) {
                for (TypeAnnotation ta : attribute.getTypeAnnotations()) {
                    int[] ranges = ta.getLocalVariableRanges();

                    if ((ranges != null) && ((ta.getTargetType() == TypeAnnotationReferences.LOCAL_VARIABLE) || (ta.getTargetType() == TypeAnnotationReferences.RESOURCE_VARIABLE))) {
                        // A resource variable is a local variable: both are printed with the target 'LOCAL_VARIABLE'
                        for (int i = 0; i < ranges.length; i += 3) {
                            AbstractLocalVariable lv = localVariableSet.get(ranges[i+2], ranges[i]);

                            if ((lv != null) && (lv.getFromOffset() == ranges[i])) {
                                if (annotationConverter == null) {
                                    annotationConverter = new AnnotationConverter(typeMaker);
                                }
                                if (lv.getTypeAnnotationReferences() == null) {
                                    lv.setTypeAnnotationReferences(new TypeAnnotationReferences());
                                }
                                lv.getTypeAnnotationReferences().add(TypeAnnotationReferences.LOCAL_VARIABLE, -1, ta.getTypePath(), annotationConverter.convertTypeAnnotation(ta));
                                break;
                            }
                        }
                    } else if ((ta.getTargetType() == TypeAnnotationReferences.CATCH) && (code.getExceptionTable() != null) &&
                               (ta.getTargetIndex() < code.getExceptionTable().length)) {
                        // 'catch (@A IOException e)': the handler starts with 'astore e', the variable starts after it
                        int handlerPc = code.getExceptionTable()[ta.getTargetIndex()].getHandlerPc();
                        int[] store = RecordPatternMaker.decodeStore(code.getCode(), handlerPc);

                        if ((store != null) && (store[0] == 'a') && catchAnnotations.add(handlerPc + ":" + ta.getTypePath() + ":" + ta.getAnnotation().getDescriptor())) {
                            AbstractLocalVariable lv = localVariableSet.get(store[1], handlerPc + store[2]);

                            if ((lv != null) && (lv.getFromOffset() == handlerPc + store[2])) {
                                if (annotationConverter == null) {
                                    annotationConverter = new AnnotationConverter(typeMaker);
                                }
                                if (lv.getTypeAnnotationReferences() == null) {
                                    lv.setTypeAnnotationReferences(new TypeAnnotationReferences());
                                }
                                lv.getTypeAnnotationReferences().add(TypeAnnotationReferences.LOCAL_VARIABLE, -1, ta.getTypePath(), annotationConverter.convertTypeAnnotation(ta));
                            }
                        }
                    }
                }
            }
        }
    }

    protected void initLocalVariablesFromAttributes(Method method) {
        AttributeCode code = method.getAttribute("Code");

        // Init local variables from attributes
        if (code != null) {
            AttributeLocalVariableTable localVariableTable = code.getAttribute("LocalVariableTable");

            if (localVariableTable != null) {
                boolean staticFlag = (method.getAccessFlags() & FLAG_STATIC) != 0;

                org.jd.core.v1.model.classfile.attribute.LocalVariable[] localVariables = localVariableTable.getLocalVariableTable();
                HashMap<org.jd.core.v1.model.classfile.attribute.LocalVariable, AbstractLocalVariable> keptLocalVariables = new HashMap<>();

                for (org.jd.core.v1.model.classfile.attribute.LocalVariable localVariable : localVariables) {
                    int index = localVariable.getIndex();
                    int startPc = (!staticFlag && index==0) ? 0 : localVariable.getStartPc();

                    if (isContinuation(code, localVariables, localVariable)) {
                        // Same variable: an unused entry would be found by a later assignment of another variable of the slot
                        continue;
                    }

                    keptLocalVariables.put(localVariable, null);

                    String descriptor = localVariable.getDescriptor();
                    String name = localVariable.getName();
                    AbstractLocalVariable lv;

                    if (descriptor.charAt(descriptor.length() - 1) == ';') {
                        lv = new ObjectLocalVariable(typeMaker, index, startPc, typeMaker.makeFromDescriptor(descriptor), name);
                    } else {
                        int dimension = TypeMaker.countDimension(descriptor);

                        if (dimension == 0) {
                            lv = new PrimitiveLocalVariable(index, startPc, PrimitiveType.getPrimitiveType(descriptor.charAt(0)), name);
                        } else {
                            lv = new ObjectLocalVariable(typeMaker, index, startPc, typeMaker.makeFromSignature(descriptor.substring(dimension)).createType(dimension), name);
                        }
                    }

                    localVariableSet.add(index, lv);
                    keptLocalVariables.put(localVariable, lv);
                    names.add(name);
                }

                // The assignments in the other ranges use the variable of the first range
                for (org.jd.core.v1.model.classfile.attribute.LocalVariable localVariable : localVariables) {
                    if (!keptLocalVariables.containsKey(localVariable)) {
                        org.jd.core.v1.model.classfile.attribute.LocalVariable first = null;

                        for (org.jd.core.v1.model.classfile.attribute.LocalVariable kept : keptLocalVariables.keySet()) {
                            if ((kept.getIndex() == localVariable.getIndex()) && isSameVariable(kept, localVariable) &&
                                    (kept.getStartPc() < localVariable.getStartPc()) && ((first == null) || (first.getStartPc() < kept.getStartPc()))) {
                                first = kept;
                            }
                        }

                        if (first != null) {
                            otherRanges.put(rangeKey(localVariable.getIndex(), localVariable.getStartPc()), keptLocalVariables.get(first));
                        }
                    }
                }
            }

            initTypeAnnotations(code);

            AttributeLocalVariableTypeTable localVariableTypeTable = code.getAttribute("LocalVariableTypeTable");

            if (localVariableTypeTable != null) {
                UpdateTypeVisitor updateTypeVisitor = new UpdateTypeVisitor(localVariableSet);

                for (LocalVariableType lv : localVariableTypeTable.getLocalVariableTypeTable()) {
                    updateTypeVisitor.setLocalVariableType(lv);
                    typeMaker.makeFromSignature(lv.getSignature()).accept(updateTypeVisitor);
                }
            }
        }
    }

    protected void initLocalVariablesFromParameterTypes(ClassFile classFile, BaseType parameterTypes, boolean varargs, int firstVariableIndex, int lastParameterIndex) {
        HashMap<Type, Boolean> typeMap = new HashMap<>();
        DefaultList<Type> t = parameterTypes.getList();

        for (int parameterIndex=0; parameterIndex<=lastParameterIndex; parameterIndex++) {
            Type type = t.get(parameterIndex);
            typeMap.put(type, Boolean.valueOf(typeMap.containsKey(type)));
        }

        String parameterNamePrefix = "param";

        if (classFile.getOuterClassFile() != null) {
            int innerTypeDepth = 1;
            ObjectType type = typeMaker.makeFromInternalTypeName(classFile.getOuterClassFile().getInternalTypeName());

            while ((type != null) && type.isInnerObjectType()) {
                innerTypeDepth++;
                type = type.getOuterType();
            }

            parameterNamePrefix += innerTypeDepth;
        }

        StringBuilder sb = new StringBuilder();
        GenerateParameterSuffixNameVisitor generateParameterSuffixNameVisitor = new GenerateParameterSuffixNameVisitor();

        for (int parameterIndex=0, variableIndex=firstVariableIndex; parameterIndex<=lastParameterIndex; parameterIndex++, variableIndex++) {
            Type type = t.get(parameterIndex);
            AbstractLocalVariable lv = localVariableSet.root(variableIndex);

            if (lv == null) {
                sb.setLength(0);
                sb.append(parameterNamePrefix);

                if ((parameterIndex == lastParameterIndex) && varargs) {
                    sb.append("VarArgs");
//                } else if (type.getDimension() > 1) {
//                    sb.append("ArrayOfArray");
                } else {
                    if (type.getDimension() > 0) {
                        sb.append("ArrayOf");
                    }
                    type.accept(generateParameterSuffixNameVisitor);
                    sb.append(generateParameterSuffixNameVisitor.getSuffix());
                }

                int length = sb.length();
                int counter = 1;

                if (typeMap.get(type)) {
                    sb.append(counter++);
                }

                String name = sb.toString();

                while (names.contains(name)) {
                    sb.setLength(length);
                    sb.append(counter++);
                    name = sb.toString();
                }

                names.add(name);
                createParameterVisitor.init(variableIndex, name);
                type.accept(createParameterVisitor);

                AbstractLocalVariable alv = createParameterVisitor.getLocalVariable();

                alv.setDeclared(true);
                localVariableSet.add(variableIndex, alv);
            }

            if (PrimitiveType.TYPE_LONG.equals(type) || PrimitiveType.TYPE_DOUBLE.equals(type)) {
                variableIndex++;
            }
        }
    }

    /**
     * javac splits the range of a variable not definitely assigned everywhere ('final int h; try { h = ...; } catch ...'):
     * the next range of the same variable does not start after an assignment.
     */
    protected static boolean isContinuation(
            AttributeCode attributeCode, org.jd.core.v1.model.classfile.attribute.LocalVariable[] localVariables,
            org.jd.core.v1.model.classfile.attribute.LocalVariable localVariable) {
        byte[] code = attributeCode.getCode();
        int index = localVariable.getIndex();
        int startPc = localVariable.getStartPc();

        if (isExceptionStore(attributeCode, startPc)) {
            // A 'catch' variable
            return false;
        }
        boolean earlier = false;

        for (org.jd.core.v1.model.classfile.attribute.LocalVariable lv : localVariables) {
            if ((lv != localVariable) && (lv.getIndex() == index) && (lv.getStartPc() < startPc) && isSameVariable(lv, localVariable)) {
                earlier = true;
                break;
            }
        }

        if (!earlier || isStoreAt(code, startPc, index)) {
            // JDK 1.1 to 1.4: the range of an exception variable starts at the store
            return false;
        }
        if (!isStoreBefore(code, startPc, index)) {
            return true;
        }

        // 'final int p; switch (c) { case 0: p = 1; break; default: p = 2; }': a range per assignment, then a range after
        // the merge point that does not start after an assignment
        for (org.jd.core.v1.model.classfile.attribute.LocalVariable lv : localVariables) {
            if ((lv.getIndex() == index) && (lv.getStartPc() > startPc) && isSameVariable(lv, localVariable) &&
                    !isStoreBefore(code, lv.getStartPc(), index) && !isStoreAt(code, lv.getStartPc(), index)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isSameVariable(org.jd.core.v1.model.classfile.attribute.LocalVariable lv1, org.jd.core.v1.model.classfile.attribute.LocalVariable lv2) {
        return lv1.getName().equals(lv2.getName()) && lv1.getDescriptor().equals(lv2.getDescriptor());
    }

    /** Does the range start at, or just after, the store of the exception at the start of a handler? */
    private static boolean isExceptionStore(AttributeCode attributeCode, int startPc) {
        if (attributeCode.getExceptionTable() != null) {
            for (CodeException codeException : attributeCode.getExceptionTable()) {
                int handlerPc = codeException.getHandlerPc();

                if ((handlerPc <= startPc) && (startPc <= handlerPc + 4)) {
                    return true;
                }
            }
        }

        return false;
    }

    /** Is the instruction at 'offset' a store into the variable 'index'? */
    private static boolean isStoreAt(byte[] code, int offset, int index) {
        if (offset >= code.length) {
            return false;
        }

        int opcode = code[offset] & 255;

        if ((opcode >= 59) && (opcode <= 78)) { // ISTORE_0 ... ASTORE_3
            return (opcode - 59) % 4 == index;
        }
        if ((opcode >= 54) && (opcode <= 58)) { // ISTORE ... ASTORE
            return (offset + 1 < code.length) && ((code[offset+1] & 255) == index);
        }
        if ((opcode == 196) && (offset + 3 < code.length) && ((code[offset+1] & 255) >= 54) && ((code[offset+1] & 255) <= 58)) { // WIDE
            return (((code[offset+2] & 255) << 8) | (code[offset+3] & 255)) == index;
        }

        return false;
    }

    /** Is the instruction before 'offset' a store into the variable 'index' ('ISTORE', 'ISTORE_n', ... 'ASTORE', 'IINC')? */
    private static boolean isStoreBefore(byte[] code, int offset, int index) {
        int opcode = code[offset-1] & 255;

        if ((opcode >= 59) && (opcode <= 78) && ((opcode - 59) % 4 == index)) { // ISTORE_0 ... ASTORE_3
            return true;
        }
        if ((offset >= 2) && ((code[offset-2] & 255) >= 54) && ((code[offset-2] & 255) <= 58) && ((code[offset-1] & 255) == index)) { // ISTORE ... ASTORE
            return true;
        }
        if ((offset >= 3) && ((code[offset-3] & 255) == 132) && ((code[offset-2] & 255) == index)) { // IINC
            return true;
        }
        if ((offset >= 4) && ((code[offset-4] & 255) == 196) && ((((code[offset-2] & 255) << 8) | (code[offset-1] & 255)) == index)) { // WIDE
            return true;
        }

        return false;
    }

    public AbstractLocalVariable getLocalVariable(int index, int offset) {
        AbstractLocalVariable lv = localVariableCache[index];

        if (lv == null) {
            lv = currentFrame.getLocalVariable(index);
//            assert lv != null : "getLocalVariable : local variable not found";
            if (lv == null) {
                lv = new ObjectLocalVariable(typeMaker, index, offset, ObjectType.TYPE_OBJECT, "SYNTHETIC_LOCAL_VARIABLE_"+index, true);
            }
        } else if (lv.getFrame() != currentFrame) {
            Frame frame = searchCommonParentFrame(lv.getFrame(), currentFrame);
            frame.mergeLocalVariable(typeBounds, this, lv);

            if (lv.getFrame() != frame) {
                lv.getFrame().removeLocalVariable(lv);
                frame.addLocalVariable(lv);
            }
        }

        lv.setToOffset(offset);

        return lv;
    }

    private static long rangeKey(int index, int offset) {
        return ((long)index << 32) | offset;
    }

    protected AbstractLocalVariable searchLocalVariable(int index, int offset) {
        AbstractLocalVariable lv = localVariableSet.get(index, offset);

        if (lv == null) {
            // The range starts after the store instruction, 'offset' is at most 2 bytes after
            for (int o = offset; (lv == null) && (o >= offset - 2); o--) {
                lv = otherRanges.get(rangeKey(index, o));
            }
        }

        if (lv == null) {
            lv = currentFrame.getLocalVariable(index);
        } else {
            AbstractLocalVariable lv2 = currentFrame.getLocalVariable(index);

            if ((lv2 != null) && ((lv.getName() == null) ? (lv2.getName() == null) : lv.getName().equals(lv2.getName())) && lv.getType().equals(lv2.getType())) {
                lv = lv2;
            }

            localVariableSet.remove(index, offset);
        }

        return lv;
    }

    public boolean isCompatible(AbstractLocalVariable lv, Type valueType) {
        if (valueType == ObjectType.TYPE_UNDEFINED_OBJECT) {
            return true;
        } else if (valueType.isObjectType() && (lv.getType().getDimension() == valueType.getDimension())) {
            ObjectType valueObjectType = (ObjectType) valueType;

            if (lv.getType().isObjectType()) {
                ObjectType lvObjectType = (ObjectType) lv.getType();

                BaseTypeArgument lvTypeArguments = lvObjectType.getTypeArguments();
                BaseTypeArgument valueTypeArguments = valueObjectType.getTypeArguments();

                if ((lvTypeArguments == null) || (valueTypeArguments == null) || (valueTypeArguments == WildcardTypeArgument.WILDCARD_TYPE_ARGUMENT)) {
                    return typeMaker.isRawTypeAssignable(lvObjectType, valueObjectType);
                }

                searchInTypeArgumentVisitor.init();
                lvTypeArguments.accept(searchInTypeArgumentVisitor);

                if (!searchInTypeArgumentVisitor.containsGeneric()) {
                    searchInTypeArgumentVisitor.init();
                    valueTypeArguments.accept(searchInTypeArgumentVisitor);

                    if (searchInTypeArgumentVisitor.containsGeneric()) {
                        return typeMaker.isRawTypeAssignable(lvObjectType, valueObjectType);
                    }
                }
            } else if (lv.getType().isGenericType() && valueObjectType.getInternalName().equals(ObjectType.TYPE_OBJECT.getInternalName())) {
                return true;
            }
        }

        return false;
    }

    public AbstractLocalVariable getLocalVariableInAssignment(Map<String, BaseType> typeBounds, int index, int offset, Type valueType) {
        AbstractLocalVariable lv = searchLocalVariable(index, offset);

        if (lv == null) {
            // Create a new local variable
            createLocalVariableVisitor.init(index, offset);
            valueType.accept(createLocalVariableVisitor);
            lv = createLocalVariableVisitor.getLocalVariable();
        } else if (lv.isAssignableFrom(typeBounds, valueType) || isCompatible(lv, valueType)) {
            // Assignable, reduce type
            lv.typeOnRight(typeBounds, valueType);
        } else if (!lv.getType().isGenericType() || (ObjectType.TYPE_OBJECT != valueType)) {
            // Not assignable -> Create a new local variable
            createLocalVariableVisitor.init(index, offset);
            valueType.accept(createLocalVariableVisitor);
            lv = createLocalVariableVisitor.getLocalVariable();
        }

        lv.setToOffset(offset);
        store(lv);

        return lv;
    }

    public AbstractLocalVariable getLocalVariableInNullAssignment(int index, int offset, Type valueType) {
        AbstractLocalVariable lv = searchLocalVariable(index, offset);

        if (lv == null) {
            // Create a new local variable
            createLocalVariableVisitor.init(index, offset);
            valueType.accept(createLocalVariableVisitor);
            lv = createLocalVariableVisitor.getLocalVariable();
        } else {
            Type type = lv.getType();

            if ((type.getDimension() == 0) && type.isPrimitiveType()) {
                // Not assignable -> Create a new local variable
                createLocalVariableVisitor.init(index, offset);
                valueType.accept(createLocalVariableVisitor);
                lv = createLocalVariableVisitor.getLocalVariable();
            }
        }

        lv.setToOffset(offset);
        store(lv);

        return lv;
    }

    public AbstractLocalVariable getLocalVariableInAssignment(Map<String, BaseType> typeBounds, int index, int offset, AbstractLocalVariable valueLocalVariable) {
        AbstractLocalVariable lv = searchLocalVariable(index, offset);

        if (lv == null) {
            // Create a new local variable
            createLocalVariableVisitor.init(index, offset);
            valueLocalVariable.accept(createLocalVariableVisitor);
            lv = createLocalVariableVisitor.getLocalVariable();
        } else if (lv.isAssignableFrom(typeBounds, valueLocalVariable) || isCompatible(lv, valueLocalVariable.getType())) {
            // Assignable
        } else if (!lv.getType().isGenericType() || (ObjectType.TYPE_OBJECT != valueLocalVariable.getType())) {
            // Not assignable -> Create a new local variable
            createLocalVariableVisitor.init(index, offset);
            valueLocalVariable.accept(createLocalVariableVisitor);
            lv = createLocalVariableVisitor.getLocalVariable();
        }

        lv.variableOnRight(typeBounds, valueLocalVariable);
        lv.setToOffset(offset);
        store(lv);

        return lv;
    }

    public AbstractLocalVariable getExceptionLocalVariable(int index, int offset, ObjectType type) {
        AbstractLocalVariable lv;

        if (index == -1) {
            currentFrame.setExceptionLocalVariable(lv = new ObjectLocalVariable(typeMaker, index, offset, type, null, true));
        } else {
            lv = localVariableSet.remove(index, offset);

            if (lv == null) {
                lv = new ObjectLocalVariable(typeMaker, index, offset, type, null, true);
            } else {
                lv.setDeclared(true);
            }

            currentFrame.addLocalVariable(lv);
        }

        return lv;
    }

    public void removeLocalVariable(AbstractLocalVariable lv) {
        int index = lv.getIndex();

        if (index < localVariableCache.length) {
            // Remove from cache
            localVariableCache[index] = null;
            // Remove from current frame
            currentFrame.removeLocalVariable(lv);
        }
    }

    protected void store(AbstractLocalVariable lv) {
        // Store to cache
        int index = lv.getIndex();

        if (index >= localVariableCache.length) {
            AbstractLocalVariable[] tmp = localVariableCache;
            localVariableCache = new AbstractLocalVariable[index * 2];
            System.arraycopy(tmp, 0, localVariableCache, 0, tmp.length);
        }

        localVariableCache[index] = lv;

        // Store to current frame
        if (lv.getFrame() == null) {
            currentFrame.addLocalVariable(lv);
        }
    }

    public boolean containsName(String name) {
        return names.contains(name);
    }

    public void make(boolean containsLineNumber, TypeMaker typeMaker) {
        currentFrame.updateLocalVariableInForStatements(typeMaker);
        currentFrame.createNames(blackListNames);
        currentFrame.createDeclarations(containsLineNumber);
    }

    public BaseFormalParameter getFormalParameters() {
        return formalParameters;
    }

    public void pushFrame(Statements statements) {
        Frame parent = currentFrame;
        currentFrame = new Frame(currentFrame, statements);
        parent.addChild(currentFrame);
    }

    public void popFrame() {
        currentFrame.close();
        currentFrame = currentFrame.getParent();
    }

    protected static Frame searchCommonParentFrame(Frame frame1, Frame frame2) {
        if (frame1 == frame2) {
            return frame1;
        }

        if (frame2.getParent() == frame1) {
            return frame1;
        }

        if (frame1.getParent() == frame2) {
            return frame2;
        }

        HashSet<Frame> set = new HashSet<>();

        while (frame1 != null) {
            set.add(frame1);
            frame1 = frame1.getParent();
        }

        while (frame2 != null) {
            if (set.contains(frame2)) {
                return frame2;
            }
            frame2 = frame2.getParent();
        }

        return null;
    }

    public void changeFrame(AbstractLocalVariable localVariable) {
        Frame frame = LocalVariableMaker.searchCommonParentFrame(localVariable.getFrame(), currentFrame);

        if (localVariable.getFrame() != frame) {
            localVariable.getFrame().removeLocalVariable(localVariable);
            frame.addLocalVariable(localVariable);
        }
    }
}
