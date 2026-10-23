/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.util;

import org.jd.core.v1.model.classfile.ClassFile;
import org.jd.core.v1.model.classfile.ConstantPool;
import org.jd.core.v1.model.classfile.Method;
import org.jd.core.v1.model.classfile.attribute.AttributeBootstrapMethods;
import org.jd.core.v1.model.classfile.attribute.AttributeCode;
import org.jd.core.v1.model.classfile.attribute.AttributeTypeAnnotations;
import org.jd.core.v1.model.classfile.attribute.TypeAnnotation;
import org.jd.core.v1.model.classfile.attribute.BootstrapMethod;
import org.jd.core.v1.model.classfile.constant.*;
import org.jd.core.v1.model.javasyntax.AbstractJavaSyntaxVisitor;
import org.jd.core.v1.model.javasyntax.declaration.*;
import org.jd.core.v1.model.javasyntax.expression.*;
import org.jd.core.v1.model.javasyntax.reference.TypeAnnotationReferences;
import org.jd.core.v1.model.javasyntax.statement.*;
import org.jd.core.v1.model.javasyntax.type.*;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.cfg.BasicBlock;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.cfg.ControlFlowGraph;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.*;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.expression.*;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.statement.ClassFileMonitorEnterStatement;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.statement.ClassFileMonitorExitStatement;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.localvariable.AbstractLocalVariable;
import org.jd.core.v1.service.converter.classfiletojavasyntax.visitor.CreateInstructionsVisitor;
import org.jd.core.v1.service.converter.classfiletojavasyntax.visitor.EraseTypeArgumentVisitor;
import org.jd.core.v1.service.converter.classfiletojavasyntax.visitor.RenameLocalVariablesVisitor;
import org.jd.core.v1.service.converter.classfiletojavasyntax.visitor.SearchFirstLineNumberVisitor;
import org.jd.core.v1.util.DefaultList;
import org.jd.core.v1.util.DefaultStack;

import java.util.*;

import static org.jd.core.v1.model.javasyntax.declaration.Declaration.FLAG_PRIVATE;
import static org.jd.core.v1.model.javasyntax.declaration.Declaration.FLAG_STATIC;
import static org.jd.core.v1.model.javasyntax.declaration.Declaration.FLAG_SYNTHETIC;
import static org.jd.core.v1.model.javasyntax.statement.ReturnStatement.RETURN;
import static org.jd.core.v1.model.javasyntax.type.ObjectType.TYPE_CLASS;
import static org.jd.core.v1.model.javasyntax.type.ObjectType.TYPE_OBJECT;
import static org.jd.core.v1.model.javasyntax.type.ObjectType.TYPE_UNDEFINED_OBJECT;
import static org.jd.core.v1.model.javasyntax.type.PrimitiveType.*;

public class ByteCodeParser {
    private static final JsrReturnAddressExpression JSR_RETURN_ADDRESS_EXPRESSION = new JsrReturnAddressExpression();

    private MemberVisitor memberVisitor = new MemberVisitor();
    private SearchFirstLineNumberVisitor searchFirstLineNumberVisitor = new SearchFirstLineNumberVisitor();
    private EraseTypeArgumentVisitor eraseTypeArgumentVisitor = new EraseTypeArgumentVisitor();
    private LambdaParameterNamesVisitor lambdaParameterNamesVisitor = new LambdaParameterNamesVisitor();;
    private RenameLocalVariablesVisitor renameLocalVariablesVisitor = new RenameLocalVariablesVisitor();

    private TypeMaker typeMaker;
    private LocalVariableMaker localVariableMaker;
    protected boolean genericTypesSupported;
    private String internalTypeName;
    private AbstractTypeParametersToTypeArgumentsBinder typeParametersToTypeArgumentsBinder;
    private AttributeBootstrapMethods attributeBootstrapMethods;
    private ClassFileBodyDeclaration bodyDeclaration;
    private Map<String, BaseType> typeBounds;
    private Type returnedType;
    private int[] switchArguments;

    protected Map<Integer, TypeAnnotationReferences> castTypeAnnotations;
    protected Map<Integer, TypeAnnotationReferences> upcastTypeAnnotations;
    protected Map<Integer, TypeAnnotationReferences> instructionTypeAnnotations; // 'instanceof' and 'new', by offset

    public ByteCodeParser(
            TypeMaker typeMaker, LocalVariableMaker localVariableMaker, ClassFile classFile,
            ClassFileBodyDeclaration bodyDeclaration, ClassFileConstructorOrMethodDeclaration comd) {
        this.typeMaker = typeMaker;
        this.localVariableMaker = localVariableMaker;
        this.genericTypesSupported = (classFile.getMajorVersion() >= 49); // (majorVersion >= Java 5)
        this.internalTypeName = classFile.getInternalTypeName();
        this.attributeBootstrapMethods = classFile.getAttribute("BootstrapMethods");
        this.bodyDeclaration = bodyDeclaration;
        this.returnedType = comd.getReturnedType();
        this.typeBounds = comd.getTypeBounds();

        if (this.genericTypesSupported) {
            this.typeParametersToTypeArgumentsBinder = new Java5TypeParametersToTypeArgumentsBinder(typeMaker, this.internalTypeName, comd);
        } else {
            this.typeParametersToTypeArgumentsBinder = new JavaTypeParametersToTypeArgumentsBinder();
        }

        this.castTypeAnnotations = makeCastTypeAnnotations(typeMaker, comd.getMethod(), true);
        this.upcastTypeAnnotations = makeCastTypeAnnotations(typeMaker, comd.getMethod(), false);
        this.instructionTypeAnnotations = makeInstructionTypeAnnotations(typeMaker, comd.getMethod());
    }

    /**
     * @return 'offset' if it is a 'checkcast', the next offset if it is one (JDK 8 javac gives the offset of the
     *         instruction that loads the operand), or -1
     */
    protected static int getCheckCastOffset(byte[] code, int offset) {
        if ((offset < 0) || (offset >= code.length)) {
            return -1;
        }
        if ((code[offset] & 255) == 192) { // CHECKCAST
            return offset;
        }

        int next = offset + RecordPatternMaker.getInstructionLength(code, offset);
        return ((next < code.length) && ((code[next] & 255) == 192)) ? next : -1;
    }

    /**
     * @return 'offset' if it consumes the value (isUpcastConsumer), the next offset if it does (JDK 8 javac gives the offset of the
     *         instruction that loads the operand), or -1. -1 if there is a 'checkcast'
     */
    protected static int getUpcastStoreOffset(byte[] code, int offset) {
        if ((offset < 0) || (offset >= code.length) || (getCheckCastOffset(code, offset) != -1)) {
            return -1;
        }
        if (isUpcastConsumer(code[offset] & 255)) {
            return offset;
        }

        int next = offset + RecordPatternMaker.getInstructionLength(code, offset);
        return ((next < code.length) && isUpcastConsumer(code[next] & 255)) ? next : -1;
    }

    /** 'astore', 'areturn', 'putstatic', 'putfield' or an invocation (not 'invokedynamic') */
    protected static boolean isUpcastConsumer(int opcode) {
        return isAStore(opcode) || (opcode == 176) || (opcode == 179) || ((opcode >= 181) && (opcode <= 185));
    }

    protected static boolean isAStore(int opcode) {
        return (opcode == 58) || ((opcode >= 75) && (opcode <= 78)); // ASTORE, ASTORE_0 ... ASTORE_3
    }

    /** 'cast' type annotations ('(@A String)o') by offset of the 'checkcast' instruction, or null */
    /**
     * @param checkCast true: by offset of the 'checkcast' instruction. false: casts without 'checkcast' ('(@A Object)s'
     *                  is an upcast, javac does not check it), by offset of the instruction that consumes the value
     *                  ('astore', 'areturn', 'putfield', 'putstatic' or an invocation)
     */
    protected static Map<Integer, TypeAnnotationReferences> makeCastTypeAnnotations(TypeMaker typeMaker, Method method, boolean checkCast) {
        AttributeCode code = method.getAttribute("Code");
        Map<Integer, TypeAnnotationReferences> map = null;

        if (code != null) {
            AnnotationConverter annotationConverter = new AnnotationConverter(typeMaker);

            for (String name : new String[] { "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations" }) {
                AttributeTypeAnnotations attribute = code.getAttribute(name);

                if (attribute != null) {
                    for (TypeAnnotation ta : attribute.getTypeAnnotations()) {
                        // 'type_argument_index' 0: not a bound of an intersection cast '(A & B)'
                        if ((ta.getTargetType() == TypeAnnotationReferences.CAST) && (ta.getSecondIndex() == 0)) {
                            int offset = checkCast ? getCheckCastOffset(code.getCode(), ta.getTargetIndex()) : getUpcastStoreOffset(code.getCode(), ta.getTargetIndex());

                            if (offset == -1) {
                                continue;
                            }
                            if (map == null) {
                                map = new HashMap<>();
                            }
                            map.computeIfAbsent(offset, k -> new TypeAnnotationReferences())
                               .add(TypeAnnotationReferences.CAST, 0, ta.getTypePath(), annotationConverter.convertTypeAnnotation(ta));
                        }
                    }
                }
            }
        }

        return map;
    }

    /** Type annotations of the 'instanceof', 'new', invocation and 'invokedynamic' instructions (0x43 ~ 0x4B), by offset */
    protected static Map<Integer, TypeAnnotationReferences> makeInstructionTypeAnnotations(TypeMaker typeMaker, Method method) {
        AttributeCode code = method.getAttribute("Code");
        Map<Integer, TypeAnnotationReferences> map = null;

        if (code != null) {
            AnnotationConverter annotationConverter = new AnnotationConverter(typeMaker);

            for (String name : new String[] { "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations" }) {
                AttributeTypeAnnotations attribute = code.getAttribute(name);

                if (attribute != null) {
                    for (TypeAnnotation ta : attribute.getTypeAnnotations()) {
                        int targetType = ta.getTargetType();

                        // 0x48 ~ 0x4B, except 0x47 (cast)
                        boolean typeArgument = (targetType >= TypeAnnotationReferences.CONSTRUCTOR_INVOCATION_TYPE_ARGUMENT) && (targetType <= TypeAnnotationReferences.METHOD_REFERENCE_TYPE_ARGUMENT);
                        int offset = (targetType == TypeAnnotationReferences.METHOD_INVOCATION_TYPE_ARGUMENT) ? getInvocationOffset(code.getCode(), ta.getTargetIndex()) : ta.getTargetIndex();

                        if (((targetType >= TypeAnnotationReferences.INSTANCEOF) && (targetType <= TypeAnnotationReferences.METHOD_REFERENCE)) || typeArgument) {
                            if (map == null) {
                                map = new HashMap<>();
                            }
                            map.computeIfAbsent(offset, k -> new TypeAnnotationReferences())
                               .add(targetType, typeArgument ? ta.getSecondIndex() : 0, ta.getTypePath(), annotationConverter.convertTypeAnnotation(ta));
                        }
                    }
                }
            }
        }

        return map;
    }

    @SuppressWarnings("unchecked")
    public void parse(BasicBlock basicBlock, Statements statements, DefaultStack<Expression> stack) {
        ControlFlowGraph cfg = basicBlock.getControlFlowGraph();
        int fromOffset = basicBlock.getFromOffset();
        int toOffset = basicBlock.getToOffset();

        Method method = cfg.getMethod();
        ConstantPool constants = method.getConstants();
        byte[] code = method.<AttributeCode>getAttribute("Code").getCode();
        boolean syntheticFlag = (method.getAccessFlags() & FLAG_SYNTHETIC) != 0;

        Expression indexRef, arrayRef, valueRef, expression1, expression2, expression3;
        TypeAnnotationReferences references;
        Type type1, type2, type3;
        ConstantMemberRef constantMemberRef;
        ConstantNameAndType constantNameAndType;
        String typeName, name, descriptor;
        ObjectType ot;
        int i, count, value;
        AbstractLocalVariable localVariable;

        Map<Integer, RecordPatternMaker.Region> recordPatterns = cfg.getRecordPatterns();

        for (int offset=fromOffset; offset<toOffset; offset++) {
            int opcode = code[offset] & 255;
            int lineNumber = syntheticFlag ? Expression.UNKNOWN_LINE_NUMBER : cfg.getLineNumber(offset);

            if (recordPatterns != null) {
                RecordPatternMaker.Region region = recordPatterns.get(offset);

                if (region != null) {
                    // Java 21+ record pattern: 'iconst_1; nop...; ifeq FAIL' or 'nop...'
                    Expression instanceOf = makeRecordPatternInstanceOf(lineNumber, offset, region);

                    if (region.getFailOffset() == -1) {
                        // Pattern switch label: see SwitchStatementMaker.makePatternSwitch
                        statements.add(new ExpressionStatement(instanceOf));
                        offset = region.getToOffset() - 1;
                    } else {
                        // Condition, then 'ifeq FAIL'
                        stack.push(instanceOf);
                        offset = region.getToOffset() - 4;
                    }
                    continue;
                }
            }

            switch (opcode) {
                case 0: // NOP
                    break;
                case 1: // ACONST_NULL
                    stack.push(new NullExpression(lineNumber, TYPE_UNDEFINED_OBJECT));
                    break;
                case 2: // ICONST_M1
                    stack.push(new IntegerConstantExpression(lineNumber, MAYBE_NEGATIVE_BYTE_TYPE, -1));
                    break;
                case 3: case 4: // ICONST_0, ICONST_1
                    stack.push(new IntegerConstantExpression(lineNumber, MAYBE_BOOLEAN_TYPE, opcode - 3));
                    break;
                case 5: case 6: case 7: case 8: // ICONST_2 ... ICONST_5
                    stack.push(new IntegerConstantExpression(lineNumber, MAYBE_BYTE_TYPE, opcode - 3));
                    break;
                case 9: case 10: // LCONST_0, LCONST_1
                    stack.push(new LongConstantExpression(lineNumber, (long)(opcode - 9)));
                    break;
                case 11: case 12: case 13: // FCONST_0, FCONST_1, FCONST_2
                    stack.push(new FloatConstantExpression(lineNumber, (float)(opcode - 11)));
                    break;
                case 14: case 15: // DCONST_0, DCONST_1
                    stack.push(new DoubleConstantExpression(lineNumber, (double)(opcode - 14)));
                    break;
                case 16: // BIPUSH
                    value = (byte)(code[++offset] & 255);
                    stack.push(new IntegerConstantExpression(lineNumber, PrimitiveTypeUtil.getPrimitiveTypeFromValue(value), value));
                    break;
                case 17: // SIPUSH
                    value = (short)(((code[++offset] & 255) << 8) | (code[++offset] & 255));
                    stack.push(new IntegerConstantExpression(lineNumber, PrimitiveTypeUtil.getPrimitiveTypeFromValue(value), value));
                    break;
                case 18: // LDC
                    parseLDC(stack, constants, lineNumber, constants.getConstant(code[++offset] & 255));
                    break;
                case 19: case 20: // LDC_W, LDC2_W
                    parseLDC(stack, constants, lineNumber, constants.getConstant(((code[++offset] & 255) << 8) | (code[++offset] & 255)));
                    break;
                case 21: // ILOAD
                    localVariable = localVariableMaker.getLocalVariable(code[++offset] & 255, offset);
                    parseILOAD(statements, stack, lineNumber, offset, localVariable);
                    break;
                case 22: case 23: case 24: // LLOAD, FLOAD, DLOAD
                    localVariable = localVariableMaker.getLocalVariable(code[++offset] & 255, offset);
                    stack.push(new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariable));
                    break;
                case 25: // ALOAD
                    i = code[++offset] & 255;
                    localVariable = localVariableMaker.getLocalVariable(i, offset);
                    if ((i == 0) && ((method.getAccessFlags() & FLAG_STATIC) == 0)) {
                        stack.push(new ThisExpression(lineNumber, localVariable.getType()));
                    } else {
                        stack.push(new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariable));
                    }
                    break;
                case 26: case 27: case 28: case 29: // ILOAD_0 ... ILOAD_3
                    localVariable = localVariableMaker.getLocalVariable(opcode - 26, offset);
                    parseILOAD(statements, stack, lineNumber, offset, localVariable);
                    break;
                case 30: case 31: case 32: case 33: // LLOAD_0 ... LLOAD_3
                    localVariable = localVariableMaker.getLocalVariable(opcode - 30, offset);
                    stack.push(new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariable));
                    break;
                case 34: case 35: case 36: case 37: // FLOAD_0 ... FLOAD_3
                    localVariable = localVariableMaker.getLocalVariable(opcode - 34, offset);
                    stack.push(new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariable));
                    break;
                case 38: case 39: case 40: case 41: // DLOAD_0 ... DLOAD_3
                    localVariable = localVariableMaker.getLocalVariable(opcode - 38, offset);
                    stack.push(new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariable));
                    break;
                case 42: // ALOAD_0
                    localVariable = localVariableMaker.getLocalVariable(0, offset);
                    if ((method.getAccessFlags() & FLAG_STATIC) == 0) {
                        stack.push(new ThisExpression(lineNumber, localVariable.getType()));
                    } else {
                        stack.push(new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariable));
                    }
                    break;
                case 43: case 44: case 45: // ALOAD_1 ... ALOAD_3
                    localVariable = localVariableMaker.getLocalVariable(opcode - 42, offset);
                    stack.push(new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariable));
                    break;
                case 46: case 47: case 48: case 49: case 50: case 51: case 52: case 53: // IALOAD, LALOAD, FALOAD, DALOAD, AALOAD, BALOAD, CALOAD, SALOAD
                    indexRef = stack.pop();
                    arrayRef = stack.pop();
                    stack.push(new ArrayExpression(lineNumber, arrayRef, indexRef));
                    break;
                case 54: case 55: case 56: case 57: // ISTORE, LSTORE, FSTORE, DSTORE
                    localVariable = getLocalVariableInAssignment(code[++offset] & 255, offset + 2, valueRef = stack.pop());
                    parseSTORE(statements, stack, lineNumber, offset, localVariable, valueRef);
                    break;
                case 58: // ASTORE
                    localVariable = getLocalVariableInAssignment(code[++offset] & 255, offset + 1, valueRef = stack.pop());
                    valueRef = addUpcast(stack, offset - 1, localVariable, valueRef);
                    parseASTORE(statements, stack, lineNumber, offset, localVariable, valueRef);
                    break;
                case 59: case 60: case 61: case 62: // ISTORE_0 ... ISTORE_3
                    localVariable = getLocalVariableInAssignment(opcode - 59, offset + 1, valueRef = stack.pop());
                    parseSTORE(statements, stack, lineNumber, offset, localVariable, valueRef);
                    break;
                case 63: case 64: case 65: case 66: // LSTORE_0 ... LSTORE_3
                    localVariable = getLocalVariableInAssignment(opcode - 63, offset + 1, valueRef = stack.pop());
                    parseSTORE(statements, stack, lineNumber, offset, localVariable, valueRef);
                    break;
                case 67: case 68: case 69: case 70: // FSTORE_0 ... FSTORE_3
                    localVariable = getLocalVariableInAssignment(opcode - 67, offset + 1, valueRef = stack.pop());
                    parseSTORE(statements, stack, lineNumber, offset, localVariable, valueRef);
                    break;
                case 71: case 72: case 73: case 74: // DSTORE_0 ... DSTORE_3
                    localVariable = getLocalVariableInAssignment(opcode - 71, offset + 1, valueRef = stack.pop());
                    parseSTORE(statements, stack, lineNumber, offset, localVariable, valueRef);
                    break;
                case 75: case 76: case 77: case 78: // ASTORE_0 ... ASTORE_3
                    localVariable = getLocalVariableInAssignment(opcode - 75, offset + 1, valueRef = stack.pop());
                    valueRef = addUpcast(stack, offset, localVariable, valueRef);
                    parseASTORE(statements, stack, lineNumber, offset, localVariable, valueRef);
                    break;
                case 79: // IASTORE
                    valueRef = stack.pop();
                    indexRef = stack.pop();
                    arrayRef = stack.pop();
                    type1 = arrayRef.getType();
                    statements.add(new ExpressionStatement(new BinaryOperatorExpression(lineNumber, type1.createType(type1.getDimension()-1), new ArrayExpression(lineNumber, arrayRef, indexRef), "=", valueRef, 16)));
                    break;
                case 80: // LASTORE
                    valueRef = stack.pop();
                    indexRef = stack.pop();
                    arrayRef = stack.pop();
                    statements.add(new ExpressionStatement(new BinaryOperatorExpression(lineNumber, TYPE_LONG, new ArrayExpression(lineNumber, arrayRef, indexRef), "=", valueRef, 16)));
                    break;
                case 81: // FASTORE
                    valueRef = stack.pop();
                    indexRef = stack.pop();
                    arrayRef = stack.pop();
                    statements.add(new ExpressionStatement(new BinaryOperatorExpression(lineNumber, TYPE_FLOAT, new ArrayExpression(lineNumber, arrayRef, indexRef), "=", valueRef, 16)));
                    break;
                case 82: // DASTORE
                    valueRef = stack.pop();
                    indexRef = stack.pop();
                    arrayRef = stack.pop();
                    statements.add(new ExpressionStatement(new BinaryOperatorExpression(lineNumber, TYPE_DOUBLE, new ArrayExpression(lineNumber, arrayRef, indexRef), "=", valueRef, 16)));
                    break;
                case 83: // AASTORE
                    valueRef = stack.pop();
                    indexRef = stack.pop();
                    arrayRef = stack.pop();
                    type1 = arrayRef.getType();
                    type2 = type1.createType(type1.getDimension()>0 ? type1.getDimension()-1 : 0);
                    typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(type2, valueRef);
                    statements.add(new ExpressionStatement(new BinaryOperatorExpression(lineNumber, type2, new ArrayExpression(lineNumber, arrayRef, indexRef), "=", valueRef, 16)));
                    break;
                case 84: // BASTORE
                    valueRef = stack.pop();
                    indexRef = stack.pop();
                    arrayRef = stack.pop();
                    statements.add(new ExpressionStatement(new BinaryOperatorExpression(lineNumber, TYPE_BYTE, new ArrayExpression(lineNumber, arrayRef, indexRef), "=", valueRef, 16)));
                    break;
                case 85: // CASTORE
                    valueRef = stack.pop();
                    indexRef = stack.pop();
                    arrayRef = stack.pop();
                    statements.add(new ExpressionStatement(new BinaryOperatorExpression(lineNumber, TYPE_CHAR, new ArrayExpression(lineNumber, arrayRef, indexRef), "=", valueRef, 16)));
                    break;
                case 86: // SASTORE
                    valueRef = stack.pop();
                    indexRef = stack.pop();
                    arrayRef = stack.pop();
                    statements.add(new ExpressionStatement(new BinaryOperatorExpression(lineNumber, TYPE_SHORT, new ArrayExpression(lineNumber, arrayRef, indexRef), "=", valueRef, 16)));
                    break;
                case 87: case 88: // POP, POP2
                    expression1 = stack.pop();
                    if (!expression1.isLocalVariableReferenceExpression() && !expression1.isFieldReferenceExpression()) {
                        typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(TYPE_OBJECT, expression1);
                        statements.add(new ExpressionStatement(expression1));
                    }
                    break;
                case 89: // DUP : ..., value => ..., value, value
                    expression1 = stack.pop();
                    stack.push(expression1);
                    stack.push(expression1);
                    break;
                case 90: // DUP_X1 : ..., value2, value1 => ..., value1, value2, value1
                    expression1 = stack.pop();
                    expression2 = stack.pop();
                    stack.push(expression1);
                    stack.push(expression2);
                    stack.push(expression1);
                    break;
                case 91: // DUP_X2
                    expression1 = stack.pop();
                    expression2 = stack.pop();

                    type2 = expression2.getType();

                    if (TYPE_LONG.equals(type2) || TYPE_DOUBLE.equals(type2)) {
                        // ..., value2, value1 => ..., value1, value2, value1
                        stack.push(expression1);
                        stack.push(expression2);
                        stack.push(expression1);
                    } else {
                        // ..., value3, value2, value1 => ..., value1, value3, value2, value1
                        expression3 = stack.pop();
                        stack.push(expression1);
                        stack.push(expression3);
                        stack.push(expression2);
                        stack.push(expression1);
                    }
                    break;
                case 92: // DUP2
                    expression1 = stack.pop();

                    type1 = expression1.getType();

                    if (TYPE_LONG.equals(type1) || TYPE_DOUBLE.equals(type1)) {
                        // ..., value => ..., value, value
                        stack.push(expression1);
                        stack.push(expression1);
                    } else {
                        // ..., value2, value1 => ..., value2, value1, value2, value1
                        expression2 = stack.pop();
                        stack.push(expression2);
                        stack.push(expression1);
                        stack.push(expression2);
                        stack.push(expression1);
                    }
                    break;
                case 93: // DUP2_X1
                    expression1 = stack.pop();
                    expression2 = stack.pop();

                    type1 = expression1.getType();

                    if (TYPE_LONG.equals(type1) || TYPE_DOUBLE.equals(type1)) {
                        // ..., value2, value1 => ..., value1, value2, value1
                        stack.push(expression1);
                        stack.push(expression2);
                        stack.push(expression1);
                    } else {
                        // ..., value3, value2, value1 => ..., value2, value1, value3, value2, value1
                        expression3 = stack.pop();
                        stack.push(expression2);
                        stack.push(expression1);
                        stack.push(expression3);
                        stack.push(expression2);
                        stack.push(expression1);
                    }
                    break;
                case 94: // DUP2_X2
                    expression1 = stack.pop();
                    expression2 = stack.pop();

                    type1 = expression1.getType();

                    if (TYPE_LONG.equals(type1) || TYPE_DOUBLE.equals(type1)) {
                        type2 = expression2.getType();

                        if (TYPE_LONG.equals(type2) || TYPE_DOUBLE.equals(type2)) {
                            // ..., value2, value1 => ..., value1, value2, value1
                            stack.push(expression1);
                            stack.push(expression2);
                            stack.push(expression1);
                        } else {
                            // ..., value3, value2, value1 => ..., value1, value3, value2, value1
                            expression3 = stack.pop();
                            stack.push(expression1);
                            stack.push(expression3);
                            stack.push(expression2);
                            stack.push(expression1);
                        }
                    } else {
                        expression3 = stack.pop();
                        type3 = expression3.getType();

                        if (TYPE_LONG.equals(type3) || TYPE_DOUBLE.equals(type3)) {
                            // ..., value3, value2, value1 => ..., value2, value1, value3, value2, value1
                            stack.push(expression2);
                            stack.push(expression1);
                            stack.push(expression3);
                            stack.push(expression2);
                            stack.push(expression1);
                        } else {
                            // ..., value4, value3, value2, value1 => ..., value2, value1, value4, value3, value2, value1
                            Expression expression4 = stack.pop();
                            stack.push(expression2);
                            stack.push(expression1);
                            stack.push(expression4);
                            stack.push(expression3);
                            stack.push(expression2);
                            stack.push(expression1);
                        }
                    }
                    break;
                case 95: // SWAP : ..., value2, value1 => ..., value1, value2
                    expression1 = stack.pop();
                    expression2 = stack.pop();
                    stack.push(expression1);
                    stack.push(expression2);
                    break;
                case 96: // IADD
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerBinaryOperatorExpression(lineNumber, expression1, "+", expression2, 6));
                    break;
                case 97: // LADD
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_LONG, expression1, "+", expression2, 6));
                    break;
                case 98: // FADD
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_FLOAT, keepFloatingPointOperation(expression1, expression2), "+", expression2, 6));
                    break;
                case 99: // DADD
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_DOUBLE, keepFloatingPointOperation(expression1, expression2), "+", expression2, 6));
                    break;
                case 100: // ISUB
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerBinaryOperatorExpression(lineNumber, expression1, "-", expression2, 6));
                    break;
                case 101: // LSUB
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_LONG, expression1, "-", expression2, 6));
                    break;
                case 102: // FSUB
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_FLOAT, keepFloatingPointOperation(expression1, expression2), "-", expression2, 6));
                    break;
                case 103: // DSUB
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_DOUBLE, keepFloatingPointOperation(expression1, expression2), "-", expression2, 6));
                    break;
                case 104: // IMUL
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerBinaryOperatorExpression(lineNumber, expression1, "*", expression2, 5));
                    break;
                case 105: // LMUL
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_LONG, expression1, "*", expression2, 5));
                    break;
                case 106: // FMUL
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_FLOAT, keepFloatingPointOperation(expression1, expression2), "*", expression2, 5));
                    break;
                case 107: // DMUL
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_DOUBLE, keepFloatingPointOperation(expression1, expression2), "*", expression2, 5));
                    break;
                case 108: // IDIV
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerBinaryOperatorExpression(lineNumber, expression1, "/", expression2, 5));
                    break;
                case 109: // LDIV
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_LONG, expression1, "/", expression2, 5));
                    break;
                case 110: // FDIV
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_FLOAT, keepFloatingPointOperation(expression1, expression2), "/", expression2, 5));
                    break;
                case 111: // DDIV
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_DOUBLE, keepFloatingPointOperation(expression1, expression2), "/", expression2, 5));
                    break;
                case 112: // IREM
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerBinaryOperatorExpression(lineNumber, expression1, "%", expression2, 5));
                    break;
                case 113: // LREM
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_LONG, expression1, "%", expression2, 5));
                    break;
                case 114: // FREM
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_FLOAT, keepFloatingPointOperation(expression1, expression2), "%", expression2, 5));
                    break;
                case 115: // DREM
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_DOUBLE, keepFloatingPointOperation(expression1, expression2), "%", expression2, 5));
                    break;
                case 116: case 117: case 118: case 119: // INEG, LNEG, FNEG, DNEG
                    stack.push(newPreArithmeticOperatorExpression(lineNumber, "-", stack.pop()));
                    break;
                case 120: // ISHL
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerBinaryOperatorExpression(lineNumber, expression1, "<<", expression2, 7));
                    break;
                case 121: // LSHL
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_LONG, expression1, "<<", expression2, 7));
                    break;
                case 122: // ISHR
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_INT, expression1, ">>", expression2, 7));
                    break;
                case 123: // LSHR
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_LONG, expression1, ">>", expression2, 7));
                    break;
                case 124: // IUSHR
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerBinaryOperatorExpression(lineNumber, expression1, ">>>", expression2, 7));
                    break;
                case 125: // LUSHR
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_LONG, expression1, ">>>", expression2, 7));
                    break;
                case 126: // IAND
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerOrBooleanBinaryOperatorExpression(lineNumber, expression1, "&", expression2, 10));
                    break;
                case 127: // LAND
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_LONG, expression1, "&", expression2, 10));
                    break;
                case 128: // IOR
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerOrBooleanBinaryOperatorExpression(lineNumber, expression1, "|", expression2, 12));
                    break;
                case 129: // LOR
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_LONG, expression1, "|", expression2, 12));
                    break;
                case 130: // IXOR
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerOrBooleanBinaryOperatorExpression(lineNumber, expression1, "^", expression2, 11));
                    break;
                case 131: // LXOR
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_LONG, expression1, "^", expression2, 11));
                    break;
                case 132: // IINC
                    localVariable = localVariableMaker.getLocalVariable(code[++offset] & 255, offset);
                    parseIINC(statements, stack, lineNumber, offset, localVariable, (byte)(code[++offset] & 255));
                    break;
                case 133: // I2L
                    stack.push(new CastExpression(lineNumber, TYPE_LONG, stack.pop(), false));
                    break;
                case 134: // I2F
                    stack.push(new CastExpression(lineNumber, TYPE_FLOAT, stack.pop(), false));
                    break;
                case 135: // I2D
                    stack.push(new CastExpression(lineNumber, TYPE_DOUBLE, stack.pop(), false));
                    break;
                case 136: // L2I
                    stack.push(new CastExpression(lineNumber, TYPE_INT, forceExplicitCastExpression(stack.pop())));
                    break;
                case 137: // L2F
                    stack.push(new CastExpression(lineNumber, TYPE_FLOAT, forceExplicitCastExpression(stack.pop())));
                    break;
                case 138: // L2D
                    stack.push(new CastExpression(lineNumber, TYPE_DOUBLE, stack.pop(), false));
                    break;
                case 139: // F2I
                    stack.push(new CastExpression(lineNumber, TYPE_INT, forceExplicitCastExpression(stack.pop())));
                    break;
                case 140: // F2L
                    stack.push(new CastExpression(lineNumber, TYPE_LONG, forceExplicitCastExpression(stack.pop())));
                    break;
                case 141: // F2D
                    stack.push(new CastExpression(lineNumber, TYPE_DOUBLE, stack.pop(), false));
                    break;
                case 142: // D2I
                    stack.push(new CastExpression(lineNumber, TYPE_INT, forceExplicitCastExpression(stack.pop())));
                    break;
                case 143: // D2L
                    stack.push(new CastExpression(lineNumber, TYPE_LONG, forceExplicitCastExpression(stack.pop())));
                    break;
                case 144: // D2F
                    stack.push(new CastExpression(lineNumber, TYPE_FLOAT, forceExplicitCastExpression(stack.pop())));
                    break;
                case 145: // I2B
                    stack.push(new CastExpression(lineNumber, TYPE_BYTE, forceExplicitCastExpression(stack.pop())));
                    break;
                case 146: // I2C
                    stack.push(new CastExpression(lineNumber, TYPE_CHAR, forceExplicitCastExpression(stack.pop())));
                    break;
                case 147: // I2S
                    stack.push(new CastExpression(lineNumber, TYPE_SHORT, forceExplicitCastExpression(stack.pop())));
                    break;
                case 148: case 149: case 150: case 151: case 152: // LCMP, FCMPL, FCMPG, DCMPL, DCMPG
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(new ClassFileCmpExpression(lineNumber, expression1, expression2));
                    break;
                case 153: // IFEQ
                    parseIF(stack, lineNumber, basicBlock, "!=", "==", 8);
                    offset += 2; // Skip branch offset
                    break;
                case 154: // IFNE
                    parseIF(stack, lineNumber, basicBlock, "==", "!=", 8);
                    offset += 2; // Skip branch offset
                    break;
                case 155: // IFLT
                    parseIF(stack, lineNumber, basicBlock, ">=", "<", 7);
                    offset += 2; // Skip branch offset
                    break;
                case 156: // IFGE
                    parseIF(stack, lineNumber, basicBlock, "<", ">=", 7);
                    offset += 2; // Skip branch offset
                    break;
                case 157: // IFGT
                    parseIF(stack, lineNumber, basicBlock, "<=", ">", 7);
                    offset += 2; // Skip branch offset
                    break;
                case 158: // IFLE
                    parseIF(stack, lineNumber, basicBlock, ">", "<=", 7);
                    offset += 2; // Skip branch offset
                    break;
                case 159: // IF_ICMPEQ
                case 165: // IF_ACMPEQ
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerOrBooleanComparisonOperatorExpression(lineNumber, expression1, basicBlock.mustInverseCondition() ? "!=" : "==", expression2, 9));
                    offset += 2; // Skip branch offset
                    break;
                case 160: // IF_ICMPNE
                case 166: // IF_ACMPNE
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerOrBooleanComparisonOperatorExpression(lineNumber, expression1, basicBlock.mustInverseCondition() ? "==" : "!=", expression2, 9));
                    offset += 2; // Skip branch offset
                    break;
                case 161: // IF_ICMPLT
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerComparisonOperatorExpression(lineNumber, expression1, basicBlock.mustInverseCondition() ? ">=" : "<", expression2, 8));
                    offset += 2; // Skip branch offset
                    break;
                case 162: // IF_ICMPGE
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerComparisonOperatorExpression(lineNumber, expression1, basicBlock.mustInverseCondition() ? "<" : ">=", expression2, 8));
                    offset += 2; // Skip branch offset
                    break;
                case 163: // IF_ICMPGT
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerComparisonOperatorExpression(lineNumber, expression1, basicBlock.mustInverseCondition() ? "<=" : ">", expression2, 8));
                    offset += 2; // Skip branch offset
                    break;
                case 164: // IF_ICMPLE
                    expression2 = stack.pop();
                    expression1 = stack.pop();
                    stack.push(newIntegerComparisonOperatorExpression(lineNumber, expression1, basicBlock.mustInverseCondition() ? ">" : "<=", expression2, 8));
                    offset += 2; // Skip branch offset
                    break;
                case 168: // JSR
                    stack.push(JSR_RETURN_ADDRESS_EXPRESSION);
                case 167: // GOTO
                    offset += 2; // Skip branch offset
                    break;
                case 169: // RET
                    offset++; // Skip index
                    break;
                case 170: // TABLESWITCH
                    offset = (offset+4) & 0xFFFC; // Skip padding
                    offset += 4; // Skip default offset

                    int low = ((code[offset++] & 255) << 24) | ((code[offset++] & 255) << 16) | ((code[offset++] & 255) << 8 ) |  (code[offset++] & 255);
                    int high = ((code[offset++] & 255) << 24) | ((code[offset++] & 255) << 16) | ((code[offset++] & 255) << 8 ) |  (code[offset++] & 255);

                    offset += (4 * (high - low + 1)) - 1;

                    statements.add(new SwitchStatement(stack.pop(), new DefaultList<>(high - low + 2)));
                    break;
                case 171: // LOOKUPSWITCH
                    offset = (offset+4) & 0xFFFC; // Skip padding
                    offset += 4; // Skip default offset

                    count = ((code[offset++] & 255) << 24) | ((code[offset++] & 255) << 16) | ((code[offset++] & 255) << 8 ) |  (code[offset++] & 255);

                    offset += (8 * count) - 1;

                    statements.add(new SwitchStatement(stack.pop(), new DefaultList<>(count+1)));
                    break;
                case 172: case 173: case 174: case 175: case 176: // IRETURN, LRETURN, FRETURN, DRETURN, ARETURN
                    if (opcode == 176) {
                        addUpcast(stack, offset, returnedType);
                    }
                    parseXRETURN(statements, stack, lineNumber);
                    break;
                case 177: // RETURN
                    statements.add(RETURN);
                    break;
                case 178: // GETSTATIC
                    parseGetStatic(stack, constants, lineNumber, ((code[++offset] & 255) << 8) | (code[++offset] & 255));
                    break;
                case 179: // PUTSTATIC
                    addFieldUpcast(stack, offset, constants, ((code[offset+1] & 255) << 8) | (code[offset+2] & 255));
                    parsePutStatic(statements, stack, constants, lineNumber, ((code[++offset] & 255) << 8) | (code[++offset] & 255));
                    break;
                case 180: // GETFIELD
                    parseGetField(stack, constants, lineNumber, ((code[++offset] & 255) << 8) | (code[++offset] & 255));
                    break;
                case 181: // PUTFIELD
                    addFieldUpcast(stack, offset, constants, ((code[offset+1] & 255) << 8) | (code[offset+2] & 255));
                    parsePutField(statements, stack, constants, lineNumber, ((code[++offset] & 255) << 8) | (code[++offset] & 255));
                    break;
                case 182: case 183: case 184: case 185: // INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE
                    TypeAnnotationReferences typeArgumentReferences = (instructionTypeAnnotations == null) ? null : instructionTypeAnnotations.get(offset);
                    references = (upcastTypeAnnotations == null) ? null : upcastTypeAnnotations.get(offset);
                    constantMemberRef = constants.getConstant( ((code[++offset] & 255) << 8) | (code[++offset] & 255) );
                    typeName = constants.getConstantTypeName(constantMemberRef.getClassIndex());
                    ot = typeMaker.makeFromDescriptorOrInternalTypeName(typeName);
                    constantNameAndType = constants.getConstant(constantMemberRef.getNameAndTypeIndex());
                    name = constants.getConstantUtf8(constantNameAndType.getNameIndex());
                    descriptor = constants.getConstantUtf8(constantNameAndType.getDescriptorIndex());
                    TypeMaker.MethodTypes methodTypes = makeMethodTypes(ot.getInternalName(), name, descriptor);
                    if ((references != null) && (methodTypes.parameterTypes != null) && (methodTypes.parameterTypes.size() == 1)) {
                        // 'm((@A Object)s)': with several parameters, the cast argument is unknown
                        addUpcast(stack, references, methodTypes.parameterTypes.getFirst());
                    }
                    BaseExpression parameters = extractParametersFromStack(statements, stack, methodTypes.parameterTypes);

                    if (opcode == 184) { // INVOKESTATIC
                        expression1 = setTypeArgumentAnnotations(typeParametersToTypeArgumentsBinder.newMethodInvocationExpression(lineNumber, new ObjectTypeReferenceExpression(lineNumber, ot), ot, name, descriptor, methodTypes, parameters), typeArgumentReferences);
                        if (TYPE_VOID.equals(methodTypes.returnedType)) {
                            typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(TYPE_OBJECT, expression1);
                            statements.add(new ExpressionStatement(expression1));
                        } else {
                            stack.push(expression1);
                        }
                    } else {
                        expression1 = stack.pop();
                        if (expression1.isLocalVariableReferenceExpression()) {
                            ((ClassFileLocalVariableReferenceExpression)expression1).getLocalVariable().typeOnLeft(typeBounds, ot);
                        }
                        checkAnonymousTypeReceiver(expression1, ot);
                        if (opcode == 185) { // INVOKEINTERFACE
                            offset += 2; // Skip 'count' and one byte
                        }
                        if (TYPE_VOID.equals(methodTypes.returnedType)) {
                            if ((opcode == 183) && // INVOKESPECIAL
                                "<init>".equals(name)) {

                                if (expression1.isNewExpression()) {
                                    typeParametersToTypeArgumentsBinder.updateNewExpression((ClassFileNewExpression)expression1, descriptor, methodTypes, parameters);
                                    if (methodTypes.typeParameters != null) {
                                        // '<T> G(T t)': the parameter types of a generic constructor are bound to the argument types
                                        ClassFileNewExpression ne = (ClassFileNewExpression)expression1;
                                        ne.setParameterTypes(bindMethodTypeParameters(ne.getParameterTypes(), methodTypes.typeParameters, getTypes(parameters)));
                                        if (ne.getTypeAnnotationReferences() != null) {
                                            // 'new <@A String>G("x")'
                                            ne.setNonWildcardTypeArguments(inferTypeArguments(methodTypes, getTypes(parameters)));
                                        }
                                    }
                                } else if (ot.getDescriptor().equals(expression1.getType().getDescriptor())) {
                                    statements.add(new ExpressionStatement(typeParametersToTypeArgumentsBinder.newConstructorInvocationExpression(lineNumber, ot, descriptor, methodTypes, parameters)));
                                } else {
                                    statements.add(new ExpressionStatement(typeParametersToTypeArgumentsBinder.newSuperConstructorInvocationExpression(lineNumber, ot, descriptor, methodTypes, parameters)));
                                }
                            } else {
                                expression1 = setTypeArgumentAnnotations(typeParametersToTypeArgumentsBinder.newMethodInvocationExpression(
                                    lineNumber, getMethodInstanceReference(opcode, constantMemberRef, expression1, ot,  name, descriptor), ot,  name, descriptor, methodTypes, parameters), typeArgumentReferences);
                                typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(TYPE_OBJECT, expression1);
                                statements.add(new ExpressionStatement(expression1));
                            }
                        } else {
                            if (opcode == 182) { // INVOKEVIRTUAL
                                if ("toString".equals(name) && "()Ljava/lang/String;".equals(descriptor)) {
                                    typeName = constants.getConstantTypeName(constantMemberRef.getClassIndex());
                                    if ("java/lang/StringBuilder".equals(typeName) || "java/lang/StringBuffer".equals(typeName)) {
                                        stack.push(StringConcatenationUtil.create(expression1, lineNumber, typeName));
                                        break;
                                    }
                                }
                            }
                            stack.push(setTypeArgumentAnnotations(typeParametersToTypeArgumentsBinder.newMethodInvocationExpression(
                                lineNumber, getMethodInstanceReference(opcode, constantMemberRef, expression1, ot,  name, descriptor), ot, name, descriptor, methodTypes, parameters), typeArgumentReferences));
                        }
                    }
                    break;
                case 186: // INVOKEDYNAMIC
                    references = (instructionTypeAnnotations == null) ? null : instructionTypeAnnotations.get(offset);
                    switchArguments = (cfg.getSwitchArguments() == null) ? null : cfg.getSwitchArguments().get(offset);
                    parseInvokeDynamic(statements, stack, constants, lineNumber, references, ((code[++offset] & 255) << 8) | (code[++offset] & 255));
                    offset += 2; // Skip 2 bytes
                    break;
                case 187: // NEW
                    references = (instructionTypeAnnotations == null) ? null : instructionTypeAnnotations.get(offset);
                    typeName = constants.getConstantTypeName( ((code[++offset] & 255) << 8) | (code[++offset] & 255) );
                    expression1 = newNewExpression(lineNumber, typeName);
                    if ((references != null) && expression1.isNewExpression()) {
                        ((NewExpression) expression1).setTypeAnnotationReferences(references);
                    }
                    stack.push(expression1);
                    break;
                case 188: // NEWARRAY
                    type1 = PrimitiveTypeUtil.getPrimitiveTypeFromTag( (code[++offset] & 255) ).createType(1);
                    stack.push(new NewArray(lineNumber, type1, stack.pop()));
                    break;
                case 189: // ANEWARRAY
                    typeName = constants.getConstantTypeName( ((code[++offset] & 255) << 8) | (code[++offset] & 255) );
                    if (typeName.charAt(0) == '[') {
                        type1 = typeMaker.makeFromDescriptor(typeName);
                        type1 = type1.createType(type1.getDimension()+1);
                    } else {
                        type1 = typeMaker.makeFromInternalTypeName(typeName).createType(1);
                    }
                    if (typeName.endsWith(TYPE_CLASS.getInternalName())) {
                        ot = (ObjectType)type1;
                        if (ot.getTypeArguments() == null) {
                            type1 = ot.createType(WildcardTypeArgument.WILDCARD_TYPE_ARGUMENT);
                        }
                    }
                    stack.push(new NewArray(lineNumber, type1, stack.pop()));
                    break;
                case 190: // ARRAYLENGTH
                    stack.push(new LengthExpression(lineNumber, stack.pop()));
                    break;
                case 191: // ATHROW
                    statements.add(new ThrowStatement(stack.pop()));
                    break;
                case 192: // CHECKCAST
                    TypeAnnotationReferences castAnnotations = (castTypeAnnotations == null) ? null : castTypeAnnotations.get(offset);
                    typeName = constants.getConstantTypeName( ((code[++offset] & 255) << 8) | (code[++offset] & 255) );
                    type1 = typeMaker.makeFromDescriptorOrInternalTypeName(typeName);
                    expression1 = stack.peek();
                    if (castAnnotations != null) {
                        // '(@A String)o': kept even when the cast is not necessary
                        searchFirstLineNumberVisitor.init();
                        expression1.accept(searchFirstLineNumberVisitor);
                        CastExpression castExpression = new CastExpression(searchFirstLineNumberVisitor.getLineNumber(), type1, forceExplicitCastExpression(stack.pop()));
                        castExpression.setTypeAnnotationReferences(castAnnotations);
                        stack.push(castExpression);
                    } else if (type1.isObjectType() && expression1.getType().isObjectType() && typeMaker.isRawTypeAssignable((ObjectType) type1, (ObjectType) expression1.getType())) {
                        // Ignore cast
                    } else if (expression1.isCastExpression()) {
                        // Skip double cast
                        ((CastExpression) expression1).setType(type1);
                    } else {
                        searchFirstLineNumberVisitor.init();
                        expression1.accept(searchFirstLineNumberVisitor);
                        stack.push(new CastExpression(searchFirstLineNumberVisitor.getLineNumber(), type1, forceExplicitCastExpression(stack.pop())));
                    }
                    break;
                case 193: // INSTANCEOF
                    references = (instructionTypeAnnotations == null) ? null : instructionTypeAnnotations.get(offset);
                    typeName = constants.getConstantTypeName( ((code[++offset] & 255) << 8) | (code[++offset] & 255) );
                    type1 = typeMaker.makeFromDescriptorOrInternalTypeName(typeName);
                    if (type1 == null) {
                        type1 = PrimitiveTypeUtil.getPrimitiveTypeFromDescriptor(typeName);
                    }
                    InstanceOfExpression instanceOfExpression = new InstanceOfExpression(lineNumber, stack.pop(), type1);
                    instanceOfExpression.setTypeAnnotationReferences(references);
                    stack.push(instanceOfExpression);
                    break;
                case 194: // MONITORENTER
                    statements.add(new ClassFileMonitorEnterStatement(stack.pop()));
                    break;
                case 195: // MONITOREXIT
                    statements.add(new ClassFileMonitorExitStatement(stack.pop()));
                    break;
                case 196: // WIDE
                    opcode = code[++offset] & 255;
                    i = ((code[++offset] & 255) << 8) | (code[++offset] & 255);

                    if (opcode == 132) { // IINC
                        count = (short)( ((code[++offset] & 255) << 8) | (code[++offset] & 255) );
                        parseIINC(statements, stack, lineNumber, offset, localVariableMaker.getLocalVariable(i, offset), count);
                    } else {
                        switch (opcode) {
                            case 21: // ILOAD
                                localVariable = localVariableMaker.getLocalVariable(i, offset + 4);
                                parseILOAD(statements, stack, offset, lineNumber, localVariable);
                                break;
                            case 22: case 23: case 24: case 25: // LLOAD, FLOAD, DLOAD, ALOAD
                                stack.push(new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariableMaker.getLocalVariable(i, offset)));
                                break;
                            case 54: // ISTORE
                                localVariable = getLocalVariableInAssignment(i, offset + 4, valueRef = stack.pop());
                                statements.add(new ExpressionStatement(new BinaryOperatorExpression(lineNumber, localVariable.getType(), new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariable), "=", valueRef, 16)));
                                break;
                            case 55: // LSTORE
                                localVariable = getLocalVariableInAssignment(i, offset + 4, valueRef = stack.pop());
                                statements.add(new ExpressionStatement(new BinaryOperatorExpression(lineNumber, TYPE_LONG, new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariable), "=", valueRef, 16)));
                                break;
                            case 56: // FSTORE
                                localVariable = getLocalVariableInAssignment(i, offset + 4, valueRef = stack.pop());
                                statements.add(new ExpressionStatement(new BinaryOperatorExpression(lineNumber, TYPE_FLOAT, new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariable), "=", valueRef, 16)));
                                break;
                            case 57: // DSTORE
                                localVariable = getLocalVariableInAssignment(i, offset + 4, valueRef = stack.pop());
                                statements.add(new ExpressionStatement(new BinaryOperatorExpression(lineNumber, TYPE_DOUBLE, new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariable), "=", valueRef, 16)));
                                break;
                            case 58: // ASTORE
                                localVariable = getLocalVariableInAssignment(i, offset + 4, valueRef = stack.pop());
                                parseASTORE(statements, stack, lineNumber, offset, localVariable, valueRef);
                                break;
                            case 169: // RET
                                break;
                        }
                    }
                    break;
                case 197: // MULTIANEWARRAY
                    typeName = constants.getConstantTypeName( ((code[++offset] & 255) << 8) | (code[++offset] & 255) );
                    type1 = typeMaker.makeFromDescriptor(typeName);
                    i = code[++offset] & 255;

                    Expressions dimensions = new Expressions(i);

                    while (i-- > 0) {
                        dimensions.add(stack.pop());
                    }

                    Collections.reverse(dimensions);
                    stack.push(new NewArray(lineNumber, type1, dimensions));
                    break;
                case 198: // IFNULL
                    expression1 = stack.pop();
                    typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(TYPE_OBJECT, expression1);
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, expression1, basicBlock.mustInverseCondition() ? "!=" : "==", new NullExpression(expression1.getLineNumber(), expression1.getType()), 9));
                    offset += 2; // Skip branch offset
                    checkStack(stack, code, offset);
                    break;
                case 199: // IFNONNULL
                    expression1 = stack.pop();
                    typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(TYPE_OBJECT, expression1);
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, expression1, basicBlock.mustInverseCondition() ? "==" : "!=", new NullExpression(expression1.getLineNumber(), expression1.getType()), 9));
                    offset += 2; // Skip branch offset
                    checkStack(stack, code, offset);
                    break;
                case 201: // JSR_W
                    stack.push(JSR_RETURN_ADDRESS_EXPRESSION);
                case 200: // GOTO_W
                    offset += 4; // Skip branch offset
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private BaseExpression extractParametersFromStack(Statements statements, DefaultStack<Expression> stack, BaseType parameterTypes) {
        if (parameterTypes == null) {
            return null;
        }

        switch (parameterTypes.size()) {
            case 0:
                return null;
            case 1:
                Expression parameter = stack.pop();
                if (parameter.isNewArray()) {
                    parameter = NewArrayMaker.make(statements, parameter);
                }
                return checkIfLastStatementIsAMultiAssignment(statements, parameter);
            default:
                Expressions parameters = new Expressions(parameterTypes.size());
                int count = parameterTypes.size() - 1;

                for (int i=count; i>=0; --i) {
                    parameter = stack.pop();
                    if (parameter.isNewArray()) {
                        parameter = NewArrayMaker.make(statements, parameter);
                    }
                    parameters.add(checkIfLastStatementIsAMultiAssignment(statements, parameter));
                }

                Collections.reverse(parameters);
                return parameters;
        }
    }

    private static Expression checkIfLastStatementIsAMultiAssignment(Statements statements, Expression parameter) {
        if (!statements.isEmpty()) {
            Expression expression = statements.getLast().getExpression();

            if (expression.isBinaryOperatorExpression() && (getLastRightExpression(expression) == parameter)) {
                // Return multi assignment expression
                statements.removeLast();
                return expression;
            }
        }

        return parameter;
    }

    private AbstractLocalVariable getLocalVariableInAssignment(int index, int offset, Expression value) {
        Type valueType = value.getType();

        if (value.isNullExpression()) {
            return localVariableMaker.getLocalVariableInNullAssignment(index, offset, valueType);
        } else if (value.isLocalVariableReferenceExpression()) {
            AbstractLocalVariable valueLocalVariable = ((ClassFileLocalVariableReferenceExpression)value).getLocalVariable();
            AbstractLocalVariable lv = localVariableMaker.getLocalVariableInAssignment(typeBounds, index, offset, valueLocalVariable);
            valueLocalVariable.variableOnLeft(typeBounds, lv);
            return lv;
        } else if (value.isMethodInvocationExpression()) {
            if (valueType.isObjectType()) {
                // Remove type arguments
                valueType = ((ObjectType)valueType).createType(null);
            } else if (valueType.isGenericType()) {
                valueType = TYPE_UNDEFINED_OBJECT;
            }
            return localVariableMaker.getLocalVariableInAssignment(typeBounds, index, offset, valueType);
        } else {
            return localVariableMaker.getLocalVariableInAssignment(typeBounds, index, offset, valueType);
        }
    }

    private void parseLDC(DefaultStack<Expression> stack, ConstantPool constants, int lineNumber, Constant constant) {
        switch (constant.getTag()) {
            case Constant.CONSTANT_Dynamic:
                // Java 11+ dynamic constant: no source form yet, fall back to byte code output
                throw new IllegalStateException("parseLDC : CONSTANT_Dynamic not supported");
            case Constant.CONSTANT_Integer:
                int i = ((ConstantInteger)constant).getValue();
                stack.push(new IntegerConstantExpression(lineNumber, PrimitiveTypeUtil.getPrimitiveTypeFromValue(i), i));
                break;
            case Constant.CONSTANT_Float:
                float f = ((ConstantFloat)constant).getValue();

                if (f == Float.MIN_VALUE) {
                    stack.push(new FieldReferenceExpression(lineNumber, TYPE_FLOAT, new ObjectTypeReferenceExpression(lineNumber, ObjectType.TYPE_FLOAT), "java/lang/Float", "MIN_VALUE", "F"));
                } else if (f == Float.MAX_VALUE) {
                    stack.push(new FieldReferenceExpression(lineNumber, TYPE_FLOAT, new ObjectTypeReferenceExpression(lineNumber, ObjectType.TYPE_FLOAT), "java/lang/Float", "MAX_VALUE", "F"));
                } else if (f == Float.NEGATIVE_INFINITY) {
                    stack.push(new FieldReferenceExpression(lineNumber, TYPE_FLOAT, new ObjectTypeReferenceExpression(lineNumber, ObjectType.TYPE_FLOAT), "java/lang/Float", "NEGATIVE_INFINITY", "F"));
                } else if (f == Float.POSITIVE_INFINITY) {
                    stack.push(new FieldReferenceExpression(lineNumber, TYPE_FLOAT, new ObjectTypeReferenceExpression(lineNumber, ObjectType.TYPE_FLOAT), "java/lang/Float", "POSITIVE_INFINITY", "F"));
                } else if (Float.isNaN(f)) {
                    stack.push(new FieldReferenceExpression(lineNumber, TYPE_FLOAT, new ObjectTypeReferenceExpression(lineNumber, ObjectType.TYPE_FLOAT), "java/lang/Float", "NaN", "F"));
                } else {
                    stack.push(new FloatConstantExpression(lineNumber, f));
                }
                break;
            case Constant.CONSTANT_Class:
                int typeNameIndex = ((ConstantClass) constant).getNameIndex();
                String typeName = ((ConstantUtf8)constants.getConstant(typeNameIndex)).getValue();
                Type type = typeMaker.makeFromDescriptorOrInternalTypeName(typeName);
                if (type == null) {
                    type = PrimitiveTypeUtil.getPrimitiveTypeFromDescriptor(typeName);
                }
                stack.push(new TypeReferenceDotClassExpression(lineNumber, type));
                break;
            case Constant.CONSTANT_Long:
                long l = ((ConstantLong)constant).getValue();

                if (l == Long.MIN_VALUE) {
                    stack.push(new FieldReferenceExpression(lineNumber, TYPE_LONG, new ObjectTypeReferenceExpression(lineNumber, ObjectType.TYPE_LONG), "java/lang/Long", "MIN_VALUE", "J"));
                } else if (l == Long.MAX_VALUE) {
                    stack.push(new FieldReferenceExpression(lineNumber, TYPE_LONG, new ObjectTypeReferenceExpression(lineNumber, ObjectType.TYPE_LONG), "java/lang/Long", "MAX_VALUE", "J"));
                } else {
                    stack.push(new LongConstantExpression(lineNumber, l));
                }
                break;
            case Constant.CONSTANT_Double:
                double d = ((ConstantDouble)constant).getValue();

                if (d == Double.MIN_VALUE) {
                    stack.push(new FieldReferenceExpression(lineNumber, TYPE_DOUBLE, new ObjectTypeReferenceExpression(lineNumber, ObjectType.TYPE_DOUBLE), "java/lang/Double", "MIN_VALUE", "D"));
                } else if (d == Double.MAX_VALUE) {
                    stack.push(new FieldReferenceExpression(lineNumber, TYPE_DOUBLE, new ObjectTypeReferenceExpression(lineNumber, ObjectType.TYPE_DOUBLE), "java/lang/Double", "MAX_VALUE", "D"));
                } else if (d == Double.NEGATIVE_INFINITY) {
                    stack.push(new FieldReferenceExpression(lineNumber, TYPE_DOUBLE, new ObjectTypeReferenceExpression(lineNumber, ObjectType.TYPE_DOUBLE), "java/lang/Double", "NEGATIVE_INFINITY", "D"));
                } else if (d == Double.POSITIVE_INFINITY) {
                    stack.push(new FieldReferenceExpression(lineNumber, TYPE_DOUBLE, new ObjectTypeReferenceExpression(lineNumber, ObjectType.TYPE_DOUBLE), "java/lang/Double", "POSITIVE_INFINITY", "D"));
                } else if (Double.isNaN(d)) {
                    stack.push(new FieldReferenceExpression(lineNumber, TYPE_DOUBLE, new ObjectTypeReferenceExpression(lineNumber, ObjectType.TYPE_DOUBLE), "java/lang/Double", "NaN", "D"));
                } else if (d == Math.E) {
                    stack.push(new FieldReferenceExpression(lineNumber, TYPE_DOUBLE, new ObjectTypeReferenceExpression(lineNumber, ObjectType.TYPE_MATH), "java/lang/Math", "E", "D"));
                } else if (d == Math.PI) {
                    stack.push(new FieldReferenceExpression(lineNumber, TYPE_DOUBLE, new ObjectTypeReferenceExpression(lineNumber, ObjectType.TYPE_MATH), "java/lang/Math", "PI", "D"));
                } else {
                    stack.push(new DoubleConstantExpression(lineNumber, d));
                }
                break;
            case Constant.CONSTANT_String:
                int stringIndex = ((ConstantString)constant).getStringIndex();
                stack.push(new StringConstantExpression(lineNumber, constants.getConstantUtf8(stringIndex)));
                break;
        }
    }

    private static void parseILOAD(Statements statements, DefaultStack<Expression> stack, int lineNumber, int offset, AbstractLocalVariable localVariable) {
        if (! statements.isEmpty()) {
            Expression expression = statements.getLast().getExpression();

            if ((expression.getLineNumber() == lineNumber) && expression.isPreOperatorExpression() && expression.getExpression().isLocalVariableReferenceExpression()) {
                ClassFileLocalVariableReferenceExpression cflvre = (ClassFileLocalVariableReferenceExpression)expression.getExpression();

                if (cflvre.getLocalVariable() == localVariable) {
                    // IINC pattern found -> Remove last statement and create a pre-incrementation
                    statements.removeLast();
                    stack.push(expression);
                    return;
                }
            }
        }

        stack.push(new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariable));
    }

    @SuppressWarnings("unchecked")
    private void parseSTORE(Statements statements, DefaultStack<Expression> stack, int lineNumber, int offset, AbstractLocalVariable localVariable, Expression valueRef) {
        ClassFileLocalVariableReferenceExpression vre = new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariable);

        typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(vre.getType(), valueRef);

        if ((valueRef.getLineNumber() == lineNumber) && valueRef.isBinaryOperatorExpression() && valueRef.getLeftExpression().isLocalVariableReferenceExpression()) {
            ClassFileLocalVariableReferenceExpression lvr = (ClassFileLocalVariableReferenceExpression)valueRef.getLeftExpression();

            if (lvr.getLocalVariable() == localVariable) {
                BinaryOperatorExpression boe = (BinaryOperatorExpression)valueRef;
                Expression expression;

                switch (boe.getOperator()) {
                    case "*": expression = createAssignment(boe, "*="); break;
                    case "/": expression = createAssignment(boe, "/="); break;
                    case "%": expression = createAssignment(boe, "%="); break;
                    case "<<": expression = createAssignment(boe, "<<="); break;
                    case ">>": expression = createAssignment(boe, ">>="); break;
                    case ">>>": expression = createAssignment(boe, ">>>="); break;
                    case "&": expression = createAssignment(boe, "&="); break;
                    case "^": expression = createAssignment(boe, "^="); break;
                    case "|": expression = createAssignment(boe, "|="); break;
                    case "=": expression = boe; break;
                    case "+":
                        if (isPositiveOne(boe.getRightExpression())) {
                            if (stackContainsLocalVariableReference(stack, localVariable)) {
                                stack.pop();
                                stack.push(valueRef);
                                expression = newPostArithmeticOperatorExpression(boe.getLineNumber(), boe.getLeftExpression(), "++");
                            } else {
                                expression = newPreArithmeticOperatorExpression(boe.getLineNumber(), "++", boe.getLeftExpression());
                            }
                        } else if (isNegativeOne(boe.getRightExpression())) {
                            if (stackContainsLocalVariableReference(stack, localVariable)) {
                                stack.pop();
                                stack.push(valueRef);
                                expression = newPostArithmeticOperatorExpression(boe.getLineNumber(), boe.getLeftExpression(), "--");
                            } else {
                                expression = newPreArithmeticOperatorExpression(boe.getLineNumber(), "--", boe.getLeftExpression());
                            }
                        } else {
                            expression = createAssignment(boe, "+=");
                        }
                        break;
                    case "-":
                        if (isPositiveOne(boe.getRightExpression())) {
                            if (stackContainsLocalVariableReference(stack, localVariable)) {
                                stack.pop();
                                stack.push(valueRef);
                                expression = newPostArithmeticOperatorExpression(boe.getLineNumber(), boe.getLeftExpression(), "--");
                            } else {
                                expression = newPreArithmeticOperatorExpression(boe.getLineNumber(), "--", boe.getLeftExpression());
                            }
                        } else if (isNegativeOne(boe.getRightExpression())) {
                            if (stackContainsLocalVariableReference(stack, localVariable)) {
                                stack.pop();
                                stack.push(valueRef);
                                expression = newPostArithmeticOperatorExpression(boe.getLineNumber(), boe.getLeftExpression(), "++");
                            } else {
                                expression = newPreArithmeticOperatorExpression(boe.getLineNumber(), "++", boe.getLeftExpression());
                            }
                        } else {
                            expression = createAssignment(boe, "-=");
                        }
                        break;
                    default: throw new RuntimeException("Unexpected value expression");
                }

                if (!stack.isEmpty() && (stack.peek() == valueRef)) {
                    stack.replace(valueRef, expression);
                } else {
                    statements.add(new ExpressionStatement(expression));
                }
                return;
            }
        }

        createAssignment(statements, stack, lineNumber, vre, valueRef);
    }

    private static boolean stackContainsLocalVariableReference(DefaultStack<Expression> stack, AbstractLocalVariable localVariable) {
        if (!stack.isEmpty()) {
            Expression expression = stack.peek();

            if (expression.isLocalVariableReferenceExpression()) {
                ClassFileLocalVariableReferenceExpression lvr = (ClassFileLocalVariableReferenceExpression) expression;
                return lvr.getLocalVariable() == localVariable;
            }
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private void parsePUT(Statements statements, DefaultStack<Expression> stack, int lineNumber, FieldReferenceExpression fr, Expression valueRef) {
        if (valueRef.isNewArray()) {
            valueRef = NewArrayMaker.make(statements, valueRef);
        }

        typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(fr.getType(), valueRef);

        if ((valueRef.getLineNumber() == lineNumber) && valueRef.isBinaryOperatorExpression() && valueRef.getLeftExpression().isFieldReferenceExpression()) {
            FieldReferenceExpression boefr = (FieldReferenceExpression)valueRef.getLeftExpression();

            if (boefr.getName().equals(fr.getName()) && boefr.getExpression().getType().equals(fr.getExpression().getType())) {
                BinaryOperatorExpression boe = (BinaryOperatorExpression)valueRef;
                Expression expression;

                switch (boe.getOperator()) {
                    case "*": expression = createAssignment(boe, "*="); break;
                    case "/": expression = createAssignment(boe, "/="); break;
                    case "%": expression = createAssignment(boe, "%="); break;
                    case "<<": expression = createAssignment(boe, "<<="); break;
                    case ">>": expression = createAssignment(boe, ">>="); break;
                    case ">>>": expression = createAssignment(boe, ">>>="); break;
                    case "&": expression = createAssignment(boe, "&="); break;
                    case "^": expression = createAssignment(boe, "^="); break;
                    case "|": expression = createAssignment(boe, "|="); break;
                    case "=": expression = boe; break;
                    case "+":
                        if (isPositiveOne(boe.getRightExpression())) {
                            if (stackContainsFieldReference(stack, fr)) {
                                stack.pop();
                                stack.push(valueRef);
                                expression = newPostArithmeticOperatorExpression(boe.getLineNumber(), boe.getLeftExpression(), "++");
                            } else {
                                expression = newPreArithmeticOperatorExpression(boe.getLineNumber(), "++", boe.getLeftExpression());
                            }
                        } else if (isNegativeOne(boe.getRightExpression())) {
                            if (stackContainsFieldReference(stack, fr)) {
                                stack.pop();
                                stack.push(valueRef);
                                expression = newPostArithmeticOperatorExpression(boe.getLineNumber(), boe.getLeftExpression(), "--");
                            } else {
                                expression = newPreArithmeticOperatorExpression(boe.getLineNumber(), "--", boe.getLeftExpression());
                            }
                        } else {
                            expression = createAssignment(boe, "+=");
                        }
                        break;
                    case "-":
                        if (isPositiveOne(boe.getRightExpression())) {
                            if (stackContainsFieldReference(stack, fr)) {
                                stack.pop();
                                stack.push(valueRef);
                                expression = newPostArithmeticOperatorExpression(boe.getLineNumber(), boe.getLeftExpression(), "--");
                            } else {
                                expression = newPreArithmeticOperatorExpression(boe.getLineNumber(), "--", boe.getLeftExpression());
                            }
                        } else if (isPositiveOne(boe.getRightExpression())) {
                            if (stackContainsFieldReference(stack, fr)) {
                                stack.pop();
                                stack.push(valueRef);
                                expression = newPostArithmeticOperatorExpression(boe.getLineNumber(), boe.getLeftExpression(), "++");
                            } else {
                                expression = newPreArithmeticOperatorExpression(boe.getLineNumber(), "++", boe.getLeftExpression());
                            }
                        } else {
                            expression = createAssignment(boe, "-=");
                        }
                        break;
                    default: throw new RuntimeException("Unexpected value expression");
                }

                if (!stack.isEmpty() && (stack.peek() == valueRef)) {
                    stack.replace(valueRef, expression);
                } else {
                    statements.add(new ExpressionStatement(expression));
                }
                return;
            }
        }

        createAssignment(statements, stack, lineNumber, fr, valueRef);
    }

    private void parseInvokeDynamic(Statements statements, DefaultStack<Expression> stack, ConstantPool constants, int lineNumber, TypeAnnotationReferences references, int index) {
        // Remove previous 'getClass()' or cast if exists
        if (! statements.isEmpty()) {
            Expression expression = statements.getLast().getExpression();

            if (expression.isMethodInvocationExpression()) {
                MethodInvocationExpression mie = (MethodInvocationExpression)expression;

                if (mie.getName().equals("getClass") && mie.getDescriptor().equals("()Ljava/lang/Class;") && mie.getInternalTypeName().equals("java/lang/Object")) {
                    statements.removeLast();
                }
            }
        }

        // Create expression
        ConstantMemberRef constantMemberRef = constants.getConstant(index);

        ConstantNameAndType indyCnat = constants.getConstant(constantMemberRef.getNameAndTypeIndex());
        String indyMethodName = constants.getConstantUtf8(indyCnat.getNameIndex());
        String indyDescriptor = constants.getConstantUtf8(indyCnat.getDescriptorIndex());
        TypeMaker.MethodTypes indyMethodTypes = typeMaker.makeMethodTypes(indyDescriptor);

        BaseExpression indyParameters = extractParametersFromStack(statements, stack, indyMethodTypes.parameterTypes);
        BootstrapMethod bootstrapMethod = attributeBootstrapMethods.getBootstrapMethods()[constantMemberRef.getClassIndex()];
        int[] bootstrapArguments = bootstrapMethod.getBootstrapArguments();

        // Dispatch on the bootstrap method, not on the call site name
        InvokeDynamicMaker invokeDynamicMaker = new InvokeDynamicMaker(typeMaker, constants, attributeBootstrapMethods.getBootstrapMethods());
        String bootstrapOwner = invokeDynamicMaker.getBootstrapMethod(bootstrapMethod)[0];

        switch (bootstrapOwner) {
            case InvokeDynamicMaker.STRING_CONCAT_FACTORY:
            case InvokeDynamicMaker.LAMBDA_METAFACTORY:
                break;
            case InvokeDynamicMaker.OBJECT_METHODS:
                // Java 16+ record toString(), hashCode() and equals(Object)
                stack.push(invokeDynamicMaker.makeObjectMethod(lineNumber, indyMethodName, bootstrapMethod, indyParameters));
                return;
            case InvokeDynamicMaker.SWITCH_BOOTSTRAPS:
                // Java 21+ pattern matching 'switch': index of the matching case label
                if ((indyParameters != null) && indyParameters.isList() && (indyParameters.size() == 2) &&
                    indyParameters.getList().get(1).isLocalVariableReferenceExpression()) {
                    // The 'restart' index is an int (javac stores 0, 1, ... in it)
                    ((ClassFileLocalVariableReferenceExpression)indyParameters.getList().get(1)).getLocalVariable().typeOnLeft(typeBounds, TYPE_INT);
                }
                if (switchArguments != null) {
                    // Labels of the cases merged by javac (see RecordPatternSwitchMaker)
                    bootstrapMethod = new BootstrapMethod(bootstrapMethod.getBootstrapMethodRef(), switchArguments);
                }
                stack.push(invokeDynamicMaker.makeSwitchIndex(lineNumber, indyMethodName, bootstrapMethod, indyParameters, indyDescriptor));
                return;
            default:
                // Unknown bootstrap method: fall back to byte code output
                throw new IllegalStateException("parseInvokeDynamic : unsupported bootstrap method " + bootstrapOwner);
        }

        if ("makeConcatWithConstants".equals(indyMethodName)) {
            // Create Java 9+ string concatenation
            String recipe = constants.getConstantString(bootstrapArguments[0]);
            // Values of the \u0002 tags of the recipe: javac only uses them for String literals containing \u0001 or \u0002
            String[] recipeConstants = new String[bootstrapArguments.length - 1];
            for (int i = 0; i < recipeConstants.length; i++) {
                recipeConstants[i] = constants.getConstantString(bootstrapArguments[i + 1]);
            }
            stack.push(StringConcatenationUtil.create(recipe, recipeConstants, indyParameters));
            return;
        } else if ("makeConcat".equals(indyMethodName)) {
            // Create Java 9+ string concatenation
            stack.push(StringConcatenationUtil.create(indyParameters));
            return;
        }

        ConstantMethodType cmt0 = constants.getConstant(bootstrapArguments[0]);
        String descriptor0 = constants.getConstantUtf8(cmt0.getDescriptorIndex());
        TypeMaker.MethodTypes methodTypes0 = typeMaker.makeMethodTypes(descriptor0);
        int parameterCount = (methodTypes0.parameterTypes == null) ? 0 : methodTypes0.parameterTypes.size();
        ConstantMethodHandle constantMethodHandle1 = constants.getConstant(bootstrapArguments[1]);
        ConstantMemberRef cmr1 = constants.getConstant(constantMethodHandle1.getReferenceIndex());
        String typeName = constants.getConstantTypeName(cmr1.getClassIndex());
        ConstantNameAndType cnat1 = constants.getConstant(cmr1.getNameAndTypeIndex());
        String name1 = constants.getConstantUtf8(cnat1.getNameIndex());
        String descriptor1 = constants.getConstantUtf8(cnat1.getDescriptorIndex());

        // 'String::trim' passed to 'map(Function<? super T, ? extends R>)': 'Function<String, String>' binds 'R'.
        // 's -> s.isEmpty()' passed to 'filter(Predicate<? super T>)': 'Predicate<String>'
        Type methodReferenceType = (bootstrapArguments.length < 3) ? indyMethodTypes.returnedType :
            makeFunctionalInterfaceType(indyMethodTypes.returnedType, indyMethodName, descriptor0, constants.getConstantUtf8(((ConstantMethodType)constants.getConstant(bootstrapArguments[2])).getDescriptorIndex()));

        if (typeName.equals(internalTypeName)) {
            for (ClassFileConstructorOrMethodDeclaration methodDeclaration : bodyDeclaration.getMethodDeclarations()) {
                if (((methodDeclaration.getFlags() & (FLAG_SYNTHETIC|FLAG_PRIVATE)) == (FLAG_SYNTHETIC|FLAG_PRIVATE)) && methodDeclaration.getMethod().getName().equals(name1) && methodDeclaration.getMethod().getDescriptor().equals(descriptor1)) {
                    // Create lambda expression
                    ClassFileMethodDeclaration cfmd = (ClassFileMethodDeclaration)methodDeclaration;
                    if (cfmd.getStatements() == null) {
                        // Nested lambda: javac may store the inner lambda method after the enclosing one
                        new CreateInstructionsVisitor(typeMaker).visit((MethodDeclaration)cfmd);
                    }
                    stack.push(new LambdaIdentifiersExpression(
                            lineNumber, methodReferenceType, indyMethodTypes.returnedType,
                            prepareLambdaParameterNames(cfmd.getFormalParameters(), parameterCount),
                            prepareLambdaStatements(cfmd.getFormalParameters(), indyParameters, (cfmd.getFlags() & FLAG_STATIC) == 0, cfmd.getStatements())));
                    return;
                }
            }
        }

        if (indyParameters == null) {
            // Create static method reference
            ObjectType ot = typeMaker.makeFromInternalTypeName(typeName);

            if (name1.equals("<init>")) {
                ConstructorReferenceExpression constructorReference = new ConstructorReferenceExpression(lineNumber, indyMethodTypes.returnedType, ot, descriptor1);
                constructorReference.setTypeAnnotationReferences(references);
                constructorReference.setNonWildcardTypeArguments(inferReferenceTypeArguments(constants, references, typeName, name1, descriptor1, bootstrapArguments));
                stack.push(constructorReference);
            } else {
                MethodReferenceExpression methodReference = new MethodReferenceExpression(lineNumber, methodReferenceType, new ObjectTypeReferenceExpression(lineNumber, ot), typeName, name1, descriptor1);
                methodReference.setTypeAnnotationReferences(references);
                methodReference.setNonWildcardTypeArguments(inferReferenceTypeArguments(constants, references, typeName, name1, descriptor1, bootstrapArguments));
                stack.push(methodReference);
            }
            return;
        }

        // Create method reference
        removeReceiverNullCheck(statements, (Expression)indyParameters);
        stack.push(new MethodReferenceExpression(lineNumber, methodReferenceType, (Expression)indyParameters, typeName, name1, descriptor1));
    }

    /** 'Objects.requireNonNull(x)' (javac 9+) or 'x.getClass()' (javac 8) written by javac before the bound method reference 'x::m' */
    private static void removeReceiverNullCheck(Statements statements, Expression receiver) {
        if (!statements.isEmpty() && statements.getLast().isExpressionStatement()) {
            Expression expression = statements.getLast().getExpression();

            if (expression.isMethodInvocationExpression()) {
                BaseExpression parameters = expression.getParameters();

                if ("requireNonNull".equals(expression.getName()) && "java/util/Objects".equals(expression.getInternalTypeName()) &&
                    (parameters != null) && (parameters.size() == 1) && (parameters.getFirst() == receiver)) {
                    statements.removeLast();
                } else if ("getClass".equals(expression.getName()) && (parameters == null) && (expression.getExpression() == receiver)) {
                    statements.removeLast();
                }
            }
        }
    }

    /**
     * 'G::<@A String>m', 'G::<@A String>new': the type arguments of the referenced method, bound with the
     * parameter types of the 'instantiatedMethodType' of LambdaMetafactory. Only computed for annotated type arguments
     */
    private BaseTypeArgument inferReferenceTypeArguments(ConstantPool constants, TypeAnnotationReferences references, String typeName, String name, String descriptor, int[] bootstrapArguments) {
        if ((references == null) || (bootstrapArguments.length < 3)) {
            return null;
        }

        TypeMaker.MethodTypes referenced = makeMethodTypes(typeName, name, descriptor);
        String instantiatedDescriptor = constants.getConstantUtf8(((ConstantMethodType)constants.getConstant(bootstrapArguments[2])).getDescriptorIndex());
        List<Type> argumentTypes = getTypes(typeMaker.makeMethodTypes(instantiatedDescriptor).parameterTypes);
        int parameterCount = (referenced.parameterTypes == null) ? 0 : referenced.parameterTypes.size();

        if (argumentTypes.size() == parameterCount + 1) {
            // 'String::m': the first argument is the receiver
            argumentTypes = argumentTypes.subList(1, argumentTypes.size());
        }

        return inferTypeArguments(referenced, argumentTypes);
    }

    /**
     * @return the type arguments of a generic method or constructor: each type parameter used as the type of a parameter
     *         ('<T> G(T t)') is bound to the type of the argument. null if one is not bound to an object type
     */
    protected static BaseTypeArgument inferTypeArguments(TypeMaker.MethodTypes methodTypes, List<Type> argumentTypes) {
        if ((methodTypes.typeParameters == null) || (methodTypes.parameterTypes == null)) {
            return null;
        }

        List<Type> parameterTypes = getTypes(methodTypes.parameterTypes);

        if (parameterTypes.size() != argumentTypes.size()) {
            return null;
        }

        TypeArguments typeArguments = new TypeArguments();

        for (TypeParameter typeParameter : methodTypes.typeParameters) {
            Type bound = null;

            for (int i = 0; i < parameterTypes.size(); i++) {
                Type parameterType = parameterTypes.get(i);

                if (parameterType.isGenericType() && (parameterType.getDimension() == 0) && typeParameter.getIdentifier().equals(parameterType.getName())) {
                    Type argumentType = argumentTypes.get(i);
                    bound = ((argumentType != null) && argumentType.isObjectType()) ? argumentType : null;
                    break;
                }
            }

            if (bound == null) {
                return null;
            }
            typeArguments.add(bound);
        }

        return (typeArguments.size() == 1) ? typeArguments.getFirst() : typeArguments;
    }

    /** The parameter types using a type parameter of the method: the type of the argument, or Object */
    protected static BaseType bindMethodTypeParameters(BaseType parameterTypes, BaseTypeParameter typeParameters, List<Type> argumentTypes) {
        List<Type> types = getTypes(parameterTypes);

        if (types.isEmpty() || (types.size() != argumentTypes.size())) {
            return parameterTypes;
        }

        Types bound = new Types();

        for (int i = 0; i < types.size(); i++) {
            Type type = types.get(i);

            if (type.isGenericType() && (type.getDimension() == 0) && isTypeParameter(typeParameters, type.getName())) {
                Type argumentType = argumentTypes.get(i);
                type = ((argumentType != null) && argumentType.isObjectType()) ? argumentType : ObjectType.TYPE_OBJECT;
            }
            bound.add(type);
        }

        return (bound.size() == 1) ? bound.getFirst() : bound;
    }

    protected static boolean isTypeParameter(BaseTypeParameter typeParameters, String name) {
        for (TypeParameter typeParameter : typeParameters) {
            if (typeParameter.getIdentifier().equals(name)) {
                return true;
            }
        }
        return false;
    }

    protected static List<Type> getTypes(BaseType types) {
        List<Type> list = new ArrayList<>();

        if (types != null) {
            for (Type type : types) {
                list.add(type);
            }
        }
        return list;
    }

    protected static List<Type> getTypes(BaseExpression expressions) {
        List<Type> list = new ArrayList<>();

        if (expressions != null) {
            for (Expression expression : expressions) {
                list.add(expression.getType());
            }
        }
        return list;
    }

    /**
     * @param rawType                the functional interface ('Function')
     * @param samName                the name of its abstract method ('apply')
     * @param samDescriptor          the erased descriptor of its abstract method ('(Ljava/lang/Object;)Ljava/lang/Object;')
     * @param instantiatedDescriptor the 'instantiatedMethodType' of LambdaMetafactory ('(Ljava/lang/String;)Ljava/lang/String;')
     * @return the parameterized type ('Function<String, String>'), or 'rawType' when a type parameter is not bound to a
     *         class without type parameters (the instantiated types are erased: 'List' may be 'List<String>')
     */
    private Type makeFunctionalInterfaceType(Type rawType, String samName, String samDescriptor, String instantiatedDescriptor) {
        if (!rawType.isObjectType() || (((ObjectType)rawType).getTypeArguments() != null) || (rawType.getDimension() != 0)) {
            return rawType;
        }

        ObjectType objectType = (ObjectType)rawType;
        TypeMaker.TypeTypes typeTypes = typeMaker.makeTypeTypes(objectType.getInternalName());

        if ((typeTypes == null) || (typeTypes.typeParameters == null)) {
            return rawType;
        }

        TypeMaker.MethodTypes sam = typeMaker.makeMethodTypes(objectType.getInternalName(), samName, samDescriptor);
        TypeMaker.MethodTypes instantiated = typeMaker.makeMethodTypes(instantiatedDescriptor);
        int samParameterCount = (sam.parameterTypes == null) ? 0 : sam.parameterTypes.size();
        int instantiatedParameterCount = (instantiated.parameterTypes == null) ? 0 : instantiated.parameterTypes.size();

        if (samParameterCount != instantiatedParameterCount) {
            return rawType;
        }

        HashMap<String, Type> bindings = new HashMap<>();

        for (int i = 0; i < samParameterCount; i++) {
            if (!bindFunctionalInterfaceTypeParameter(bindings, sam.parameterTypes.getList().get(i), instantiated.parameterTypes.getList().get(i))) {
                return rawType;
            }
        }
        if (!bindFunctionalInterfaceTypeParameter(bindings, sam.returnedType, instantiated.returnedType)) {
            return rawType;
        }

        DefaultList<TypeParameter> typeParameters = new DefaultList<>();

        if (typeTypes.typeParameters.isList()) {
            for (TypeParameter typeParameter : (TypeParameters)typeTypes.typeParameters) {
                typeParameters.add(typeParameter);
            }
        } else {
            typeParameters.add(typeTypes.typeParameters.getFirst());
        }

        TypeArguments typeArguments = new TypeArguments(typeParameters.size());

        for (TypeParameter typeParameter : typeParameters) {
            Type type = bindings.get(typeParameter.getIdentifier());

            if (!(type instanceof ObjectType)) {
                return rawType;
            }
            typeArguments.add((ObjectType)type);
        }

        return objectType.createType((typeArguments.size() == 1) ? typeArguments.getFirst() : typeArguments);
    }

    /** @return false if 'samType' is a type parameter bound to two different types */
    private boolean bindFunctionalInterfaceTypeParameter(HashMap<String, Type> bindings, Type samType, Type instantiatedType) {
        if (!samType.isGenericType() || (samType.getDimension() != 0) || !instantiatedType.isObjectType() || (instantiatedType.getDimension() != 0)) {
            return true;
        }
        if (TYPE_OBJECT.equals(instantiatedType)) {
            // Erased type variable ('item -> ...' in a generic method '<T> m(List<T>)'): not bound
            return true;
        }

        TypeMaker.TypeTypes typeTypes = typeMaker.makeTypeTypes(((ObjectType)instantiatedType).getInternalName());

        if ((typeTypes == null) || (typeTypes.typeParameters != null)) {
            // An erased generic type ('List' may be 'List<String>'): not bound
            return true;
        }

        Type previous = bindings.put(samType.getName(), instantiatedType);
        return (previous == null) || previous.equals(instantiatedType);
    }

    private List<String> prepareLambdaParameterNames(BaseFormalParameter formalParameters, int parameterCount) {
        if ((formalParameters == null) || (parameterCount == 0)) {
            return null;
        } else {
            lambdaParameterNamesVisitor.init();
            formalParameters.accept(lambdaParameterNamesVisitor);
            List<String> names = lambdaParameterNamesVisitor.getNames();

            assert names.size() >= parameterCount;

            if (names.size() == parameterCount) {
                return names;
            } else {
                return names.subList(names.size() - parameterCount, names.size());
            }
        }
    }

    /**
     * @param hasReceiver true for an instance lambda method: the first 'invokedynamic' argument is 'this', not a captured variable
     */
    private BaseStatement prepareLambdaStatements(BaseFormalParameter formalParameters, BaseExpression indyParameters, boolean hasReceiver, BaseStatement baseStatement) {
        if (baseStatement != null) {
            if ((formalParameters != null) && (indyParameters != null)) {
                int offset = hasReceiver ? 1 : 0;
                int size = indyParameters.size() - offset;

                if ((size > 0) && (size <= formalParameters.size())) {
                    HashMap<String, String> mapping = new HashMap<>();

                    for (int i = 0; i < size; i++) {
                        Expression expression = indyParameters.isList() ? indyParameters.getList().get(i + offset) : indyParameters.getFirst();

                        if (expression.isLocalVariableReferenceExpression()) {
                            String name = (formalParameters.isList() ? formalParameters.getList().get(i) : formalParameters.getFirst()).getName();
                            String newName = expression.getName();

                            if (!name.equals(newName)) {
                                mapping.put(name, newName);
                            }
                        }
                    }

                    if (!mapping.isEmpty()) {
                        renameLocalVariablesVisitor.init(mapping);
                        baseStatement.accept(renameLocalVariablesVisitor);
                    }
                }
            }

            if (baseStatement.size() == 1) {
                Statement statement = baseStatement.getFirst();

                if (statement.isReturnExpressionStatement()) {
                    return new LambdaExpressionStatement(statement.getExpression());
                } else if (statement.isExpressionStatement()) {
                    return new LambdaExpressionStatement(statement.getExpression());
                }
            }
        }

        return baseStatement;
    }

    private static boolean stackContainsFieldReference(DefaultStack<Expression> stack, FieldReferenceExpression fr) {
        if (!stack.isEmpty()) {
            Expression expression = stack.peek();

            if (expression.isFieldReferenceExpression()) {
                return expression.getName().equals(fr.getName()) && expression.getExpression().getType().equals(fr.getExpression().getType());
            }
        }

        return false;
    }

    private static Expression createAssignment(BinaryOperatorExpression boe, String operator) {
        boe.setOperator(operator);
        boe.setPriority(16);
        return boe;
    }

    private static boolean isPositiveOne(Expression expression) {
        if (expression.isIntegerConstantExpression() && expression.getIntegerValue() == 1)
            return true;
        if (expression.isLongConstantExpression() && expression.getLongValue() == 1L)
            return true;
        if (expression.isFloatConstantExpression() && expression.getFloatValue() == 1.0F)
            return true;
        return (expression.isDoubleConstantExpression() && expression.getDoubleValue() == 1.0D);
    }

    private static boolean isNegativeOne(Expression expression) {
        if (expression.isIntegerConstantExpression() && expression.getIntegerValue() == -1)
            return true;
        if (expression.isLongConstantExpression() && expression.getLongValue() == -1L)
            return true;
        if (expression.isFloatConstantExpression() && expression.getFloatValue() == -1.0F)
            return true;
        return (expression.isDoubleConstantExpression() && expression.getDoubleValue() == -1.0D);
    }

    /**
     * '(@A Object)s': the type annotation of a cast without 'checkcast'. Any upcast compiles to the same byte code,
     * the type of the variable is the cast type
     */
    private Expression addUpcast(DefaultStack<Expression> stack, int offset, AbstractLocalVariable localVariable, Expression valueRef) {
        TypeAnnotationReferences references = (upcastTypeAnnotations == null) ? null : upcastTypeAnnotations.get(offset);

        if ((references == null) || !localVariable.getType().isObjectType() || (!stack.isEmpty() && (stack.peek() == valueRef))) {
            return valueRef;
        }

        CastExpression castExpression = new CastExpression(valueRef.getLineNumber(), localVariable.getType(), valueRef, true);
        castExpression.setTypeAnnotationReferences(references);
        return castExpression;
    }

    /**
     * '(@A Object)s' consumed by 'areturn', 'putfield', 'putstatic' or an invocation: the cast type is supposed to be
     * the type expected by the instruction
     */
    private void addUpcast(DefaultStack<Expression> stack, int offset, Type type) {
        addUpcast(stack, (upcastTypeAnnotations == null) ? null : upcastTypeAnnotations.get(offset), type);
    }

    private void addUpcast(DefaultStack<Expression> stack, TypeAnnotationReferences references, Type type) {
        if ((references == null) || (type == null) || !type.isObjectType() || stack.isEmpty()) {
            return;
        }

        Expression valueRef = stack.pop();
        CastExpression castExpression = new CastExpression(valueRef.getLineNumber(), type, valueRef, true);
        castExpression.setTypeAnnotationReferences(references);
        stack.push(castExpression);
    }

    /**
     * @return the offset of the invocation: javac gives the offset of the 'checkcast' of the returned value when
     *         there is one
     */
    protected static int getInvocationOffset(byte[] code, int offset) {
        if ((offset < 0) || (offset >= code.length) || ((code[offset] & 255) != 192)) { // CHECKCAST
            return offset;
        }

        int previous = -1;

        for (int i = 0; i < offset; i += RecordPatternMaker.getInstructionLength(code, i)) {
            previous = i;
        }

        return (previous == -1) ? offset : previous;
    }

    /** 'this.<@A String>m()': the annotations of the explicit type arguments */
    private static Expression setTypeArgumentAnnotations(Expression expression, TypeAnnotationReferences references) {
        if ((references != null) && expression.isMethodInvocationExpression()) {
            ((MethodInvocationExpression)expression).setTypeAnnotationReferences(references);
        }
        return expression;
    }

    private void addFieldUpcast(DefaultStack<Expression> stack, int offset, ConstantPool constants, int index) {
        TypeAnnotationReferences references = (upcastTypeAnnotations == null) ? null : upcastTypeAnnotations.get(offset);

        if (references != null) {
            ConstantMemberRef constantMemberRef = constants.getConstant(index);
            ConstantNameAndType constantNameAndType = constants.getConstant(constantMemberRef.getNameAndTypeIndex());
            String descriptor = constants.getConstantUtf8(constantNameAndType.getDescriptorIndex());

            if ((descriptor.charAt(0) == 'L') || (descriptor.charAt(0) == '[')) {
                addUpcast(stack, references, typeMaker.makeFromDescriptor(descriptor));
            }
        }
    }

    private void parseASTORE(Statements statements, DefaultStack<Expression> stack, int lineNumber, int offset, AbstractLocalVariable localVariable, Expression valueRef) {
        Type type = localVariable.getType();
        // A new synthetic variable ('return' value stored before 'finally'): the type arguments come from the arguments
        typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes((type == TYPE_UNDEFINED_OBJECT) ? TYPE_OBJECT : type, valueRef);
        localVariable.typeOnRight(typeBounds, valueRef.getType());

        ClassFileLocalVariableReferenceExpression vre = new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariable);
        Expression oldValueRef = valueRef;

        if (valueRef.isNewArray()) {
            valueRef = NewArrayMaker.make(statements, valueRef);
        }

        if (oldValueRef != valueRef) {
            stack.replace(oldValueRef, valueRef);
        }

        createAssignment(statements, stack, lineNumber, vre, valueRef);
    }

    @SuppressWarnings("unchecked")
    private void createAssignment(Statements statements, DefaultStack<Expression> stack, int lineNumber, Expression leftExpression, Expression rightExpression) {
        if (!stack.isEmpty() && (stack.peek() == rightExpression)) {
            stack.push(new BinaryOperatorExpression(lineNumber, leftExpression.getType(), leftExpression, "=", stack.pop(), 16));
            return;
        }

        if (!statements.isEmpty()) {
            Statement lastStatement = statements.getLast();

            if (lastStatement.isExpressionStatement()) {
                ExpressionStatement lastES = (ExpressionStatement)lastStatement;
                Expression lastExpression = lastStatement.getExpression();

                if (lastExpression.isBinaryOperatorExpression()) {
                    if (getLastRightExpression(lastExpression) == rightExpression) {
                        // Multi assignment
                        lastES.setExpression(new BinaryOperatorExpression(lineNumber, leftExpression.getType(), leftExpression, "=", lastExpression, 16));
                        return;
                    }

                    if ((lineNumber > 0) && (lastExpression.getLineNumber() == lineNumber) && (lastExpression.getLeftExpression().getClass() == rightExpression.getClass())) {
                        if (rightExpression.isLocalVariableReferenceExpression()) {
                            ClassFileLocalVariableReferenceExpression lvr1 = (ClassFileLocalVariableReferenceExpression) lastExpression.getLeftExpression();
                            ClassFileLocalVariableReferenceExpression lvr2 = (ClassFileLocalVariableReferenceExpression) rightExpression;

                            if (lvr1.getLocalVariable() == lvr2.getLocalVariable()) {
                                // Multi assignment
                                lastES.setExpression(new BinaryOperatorExpression(lineNumber, leftExpression.getType(), leftExpression, "=", lastExpression, 16));
                                return;
                            }
                        } else if (rightExpression.isFieldReferenceExpression()) {
                            FieldReferenceExpression fr1 = (FieldReferenceExpression) lastExpression.getLeftExpression();
                            FieldReferenceExpression fr2 = (FieldReferenceExpression) rightExpression;

                            if (fr1.getName().equals(fr2.getName()) && fr1.getExpression().getType().equals(fr2.getExpression().getType())) {
                                // Multi assignment
                                lastES.setExpression(new BinaryOperatorExpression(lineNumber, leftExpression.getType(), leftExpression, "=", lastExpression, 16));
                                return;
                            }
                        }
                    }
                } else if (lastExpression.isPreOperatorExpression()) {
                    if (lastExpression.getExpression().getClass() == rightExpression.getClass()) {
                        if (rightExpression.isLocalVariableReferenceExpression()) {
                            ClassFileLocalVariableReferenceExpression lvr1 = (ClassFileLocalVariableReferenceExpression)lastExpression.getExpression();
                            ClassFileLocalVariableReferenceExpression lvr2 = (ClassFileLocalVariableReferenceExpression)rightExpression;

                            if (lvr1.getLocalVariable() == lvr2.getLocalVariable()) {
                                rightExpression = newPreArithmeticOperatorExpression(lastExpression.getLineNumber(), lastExpression.getOperator(), lastExpression.getExpression());
                                statements.removeLast();
                            }
                        } else if (rightExpression.isFieldReferenceExpression()) {
                            FieldReferenceExpression fr1 = (FieldReferenceExpression)lastExpression.getExpression();
                            FieldReferenceExpression fr2 = (FieldReferenceExpression)rightExpression;

                            if (fr1.getName().equals(fr2.getName()) && fr1.getExpression().getType().equals(fr2.getExpression().getType())) {
                                rightExpression = newPreArithmeticOperatorExpression(lastExpression.getLineNumber(), lastExpression.getOperator(), lastExpression.getExpression());
                                statements.removeLast();
                            }
                        }
                    }
                } else if (lastExpression.isPostOperatorExpression()) {
                    PostOperatorExpression poe = (PostOperatorExpression)lastExpression;

                    if (poe.getExpression() == rightExpression) {
                        rightExpression = poe;
                        statements.removeLast();
                    }
                }
            }
        }

        statements.add(new ExpressionStatement(new BinaryOperatorExpression(lineNumber, leftExpression.getType(), leftExpression, "=", rightExpression, 16)));
    }

    @SuppressWarnings("unchecked")
    private void parseIINC(Statements statements, DefaultStack<Expression> stack, int lineNumber, int offset, AbstractLocalVariable localVariable, int count) {
        Expression expression;

        if (!stack.isEmpty()) {
            expression = stack.peek();

            if ((expression.getLineNumber() == lineNumber) && expression.isLocalVariableReferenceExpression()) {
                ClassFileLocalVariableReferenceExpression exp = (ClassFileLocalVariableReferenceExpression)expression;

                if (exp.getLocalVariable() == localVariable) {
                    // ILOAD found -> Create a post-incrementation
                    stack.pop();

                    if (count == 1) {
                        stack.push(newPostArithmeticOperatorExpression(lineNumber, expression, "++"));
                    } else if (count == -1) {
                        stack.push(newPostArithmeticOperatorExpression(lineNumber, expression, "--"));
                    } else {
                        assert false;
                    }

                    return;
                }
            }
        }

        expression = new ClassFileLocalVariableReferenceExpression(lineNumber, offset, localVariable);

        if (count == 1) {
            expression = newPreArithmeticOperatorExpression(lineNumber, "++", expression);
        } else if (count == -1) {
            expression = newPreArithmeticOperatorExpression(lineNumber, "--", expression);
        } else if (count >= 0) {
            // IINC: int arithmetic. The type of the variable may still be undecided here ('0' is also 'false'):
            // a snapshot of it would later make the constant 'true' ('i += true')
            expression = new BinaryOperatorExpression(lineNumber, TYPE_INT, expression, "+=", new IntegerConstantExpression(lineNumber, TYPE_INT, count), 16);
        } else if (count < 0) {
            expression = new BinaryOperatorExpression(lineNumber, TYPE_INT, expression, "-=", new IntegerConstantExpression(lineNumber, TYPE_INT, -count), 16);
        } else {
            assert false;
            expression = null;
        }

        statements.add(new ExpressionStatement(expression));
    }

    private void parseIF(DefaultStack<Expression> stack, int lineNumber, BasicBlock basicBlock, String operator1, String operator2, int priority) {
        Expression expression = stack.pop();

        if (expression.getClass() == ClassFileCmpExpression.class) {
            ClassFileCmpExpression cmp = (ClassFileCmpExpression)expression;

            typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(cmp.getLeftExpression().getType(), cmp.getLeftExpression());
            typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(cmp.getRightExpression().getType(), cmp.getRightExpression());

            stack.push(new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, cmp.getLeftExpression(), (basicBlock.mustInverseCondition() ? operator1 : operator2), cmp.getRightExpression(), priority));
        } else if (expression.getType().isPrimitiveType()) {
            PrimitiveType pt = (PrimitiveType)expression.getType();

            typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(pt, expression);

            switch (pt.getJavaPrimitiveFlags()) {
                case FLAG_BOOLEAN:
                    if (basicBlock.mustInverseCondition() ^ "==".equals(operator1))
                        stack.push(expression);
                    else
                        stack.push(new PreOperatorExpression(lineNumber, "!", expression));
                    break;
                case FLAG_FLOAT:
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, expression, (basicBlock.mustInverseCondition() ? operator1 : operator2), new FloatConstantExpression(lineNumber, 0), 9));
                    break;
                case FLAG_DOUBLE:
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, expression, (basicBlock.mustInverseCondition() ? operator1 : operator2), new DoubleConstantExpression(lineNumber, 0), 9));
                    break;
                case FLAG_LONG:
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, expression, (basicBlock.mustInverseCondition() ? operator1 : operator2), new LongConstantExpression(lineNumber, 0), 9));
                    break;
                default:
                    stack.push(new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, expression, (basicBlock.mustInverseCondition() ? operator1 : operator2), new IntegerConstantExpression(lineNumber, pt, 0), 9));
                    break;
            }
        } else {
            stack.push(new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, expression, (basicBlock.mustInverseCondition() ? operator1 : operator2), new NullExpression(lineNumber, expression.getType()), 9));
        }
    }

    @SuppressWarnings("unchecked")
    private void parseXRETURN(Statements statements, DefaultStack<Expression> stack, int lineNumber) {
        Expression valueRef = stack.pop();

        if (valueRef.isNewArray()) {
            valueRef = NewArrayMaker.make(statements, valueRef);
        }

        typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(returnedType, valueRef);

        if (lineNumber > valueRef.getLineNumber()) {
            lineNumber = valueRef.getLineNumber();
        }

        if (!statements.isEmpty() && valueRef.isLocalVariableReferenceExpression()) {
            Statement lastStatement = statements.getLast();

            if (lastStatement.isExpressionStatement()) {
                Expression expression = statements.getLast().getExpression();

                if ((lineNumber <= expression.getLineNumber()) && expression.isBinaryOperatorExpression() &&
                    expression.getOperator().equals("=") && expression.getLeftExpression().isLocalVariableReferenceExpression()) {
                    ClassFileLocalVariableReferenceExpression vre1 = (ClassFileLocalVariableReferenceExpression) expression.getLeftExpression();
                    ClassFileLocalVariableReferenceExpression vre2 = (ClassFileLocalVariableReferenceExpression) valueRef;

                    if (vre1.getLocalVariable() == vre2.getLocalVariable()) {
                        // Remove synthetic local variable
                        localVariableMaker.removeLocalVariable(vre1.getLocalVariable());
                        // Remove assignment statement
                        statements.removeLast();
                        statements.add(new ReturnExpressionStatement(lineNumber, expression.getRightExpression()));
                        return;
                    }
                }
            }
        }

        statements.add(new ReturnExpressionStatement(lineNumber, valueRef));
    }

    private void parseGetStatic(DefaultStack<Expression> stack, ConstantPool constants, int lineNumber, int index) {
        ConstantMemberRef constantMemberRef = constants.getConstant(index);
        String typeName = constants.getConstantTypeName(constantMemberRef.getClassIndex());
        ConstantNameAndType constantNameAndType = constants.getConstant(constantMemberRef.getNameAndTypeIndex());
        String name = constants.getConstantUtf8(constantNameAndType.getNameIndex());

        if (name.equals("TYPE") && typeName.startsWith("java/lang/")) {
            switch (typeName) {
                case "java/lang/Boolean":   stack.push(new TypeReferenceDotClassExpression(lineNumber, TYPE_BOOLEAN)); return;
                case "java/lang/Character": stack.push(new TypeReferenceDotClassExpression(lineNumber, TYPE_CHAR));    return;
                case "java/lang/Float":     stack.push(new TypeReferenceDotClassExpression(lineNumber, TYPE_FLOAT));   return;
                case "java/lang/Double":    stack.push(new TypeReferenceDotClassExpression(lineNumber, TYPE_DOUBLE));  return;
                case "java/lang/Byte":      stack.push(new TypeReferenceDotClassExpression(lineNumber, TYPE_BYTE));    return;
                case "java/lang/Short":     stack.push(new TypeReferenceDotClassExpression(lineNumber, TYPE_SHORT));   return;
                case "java/lang/Integer":   stack.push(new TypeReferenceDotClassExpression(lineNumber, TYPE_INT));     return;
                case "java/lang/Long":      stack.push(new TypeReferenceDotClassExpression(lineNumber, TYPE_LONG));    return;
                case "java/lang/Void":      stack.push(new TypeReferenceDotClassExpression(lineNumber, TYPE_VOID));    return;
            }
        }

        ObjectType ot = typeMaker.makeFromInternalTypeName(typeName);
        String descriptor = constants.getConstantUtf8(constantNameAndType.getDescriptorIndex());
        Type type = makeFieldType(ot.getInternalName(), name, descriptor);
        Expression objectRef = new ObjectTypeReferenceExpression(lineNumber, ot, !internalTypeName.equals(typeName) || localVariableMaker.containsName(name));
        stack.push(typeParametersToTypeArgumentsBinder.newFieldReferenceExpression(lineNumber, type, objectRef, ot, name, descriptor));
    }

    private void parsePutStatic(Statements statements, DefaultStack<Expression> stack, ConstantPool constants, int lineNumber, int index) {
        ConstantMemberRef constantMemberRef = constants.getConstant(index);
        String typeName = constants.getConstantTypeName(constantMemberRef.getClassIndex());
        ObjectType ot = typeMaker.makeFromInternalTypeName(typeName);
        ConstantNameAndType constantNameAndType = constants.getConstant(constantMemberRef.getNameAndTypeIndex());
        String name = constants.getConstantUtf8(constantNameAndType.getNameIndex());
        String descriptor = constants.getConstantUtf8(constantNameAndType.getDescriptorIndex());
        Type type = makeFieldType(ot.getInternalName(), name, descriptor);
        Expression valueRef = stack.pop();
        Expression objectRef = new ObjectTypeReferenceExpression(lineNumber, ot, !internalTypeName.equals(typeName) || localVariableMaker.containsName(name));
        FieldReferenceExpression fieldRef = typeParametersToTypeArgumentsBinder.newFieldReferenceExpression(lineNumber, type, objectRef, ot, name, descriptor);
        parsePUT(statements, stack, lineNumber, fieldRef, valueRef);
    }

    private void parseGetField(DefaultStack<Expression> stack, ConstantPool constants, int lineNumber, int index) {
        ConstantMemberRef constantMemberRef = constants.getConstant(index);
        String typeName = constants.getConstantTypeName(constantMemberRef.getClassIndex());
        ObjectType ot = typeMaker.makeFromInternalTypeName(typeName);
        ConstantNameAndType constantNameAndType = constants.getConstant(constantMemberRef.getNameAndTypeIndex());
        String name = constants.getConstantUtf8(constantNameAndType.getNameIndex());
        String descriptor = constants.getConstantUtf8(constantNameAndType.getDescriptorIndex());
        Type type = makeFieldType(ot.getInternalName(), name, descriptor);
        Expression objectRef = stack.pop();
        checkAnonymousTypeReceiver(objectRef, ot);
        stack.push(typeParametersToTypeArgumentsBinder.newFieldReferenceExpression(lineNumber, type, getFieldInstanceReference(objectRef, ot,  name), ot, name, descriptor));
    }

    private void parsePutField(Statements statements, DefaultStack<Expression> stack, ConstantPool constants, int lineNumber, int index) {
        ConstantMemberRef constantMemberRef = constants.getConstant(index);
        String typeName = constants.getConstantTypeName(constantMemberRef.getClassIndex());
        ObjectType ot = typeMaker.makeFromInternalTypeName(typeName);
        ConstantNameAndType constantNameAndType = constants.getConstant(constantMemberRef.getNameAndTypeIndex());
        String name = constants.getConstantUtf8(constantNameAndType.getNameIndex());
        String descriptor = constants.getConstantUtf8(constantNameAndType.getDescriptorIndex());
        Type type = makeFieldType(ot.getInternalName(), name, descriptor);
        Expression valueRef = stack.pop();
        Expression objectRef = stack.pop();
        checkAnonymousTypeReceiver(objectRef, ot);
        FieldReferenceExpression fieldRef = typeParametersToTypeArgumentsBinder.newFieldReferenceExpression(lineNumber, type, getFieldInstanceReference(objectRef, ot,  name), ot, name, descriptor);
        parsePUT(statements, stack, lineNumber, fieldRef, valueRef);
    }

    private static Expression getLastRightExpression(Expression boe) {
        while (true) {
            if (! boe.getOperator().equals("=")) {
                return boe;
            }

            if (! boe.getRightExpression().isBinaryOperatorExpression()) {
                return boe.getRightExpression();
            }

            boe = boe.getRightExpression();
        }
    }

    private Expression newNewExpression(int lineNumber, String internalTypeName) {
        ObjectType objectType = typeMaker.makeFromInternalTypeName(internalTypeName);

        if ((objectType.getQualifiedName() == null) && (objectType.getName() == null)) {
            ClassFileTypeDeclaration typeDeclaration = bodyDeclaration.getInnerTypeDeclaration(internalTypeName);

            if (typeDeclaration == null) {
                return new ClassFileNewExpression(lineNumber, ObjectType.TYPE_OBJECT);
            } else if (typeDeclaration.isClassDeclaration()) {
                ClassFileClassDeclaration declaration = (ClassFileClassDeclaration) typeDeclaration;
                BodyDeclaration bodyDeclaration;

                if (this.internalTypeName.equals(internalTypeName)) {
                    bodyDeclaration = null;
                } else {
                    bodyDeclaration = declaration.getBodyDeclaration();
                }

                if (declaration.getInterfaces() != null) {
                    return new ClassFileNewExpression(lineNumber, (ObjectType) declaration.getInterfaces(), bodyDeclaration, true);
                } else if (declaration.getSuperType() != null) {
                    return new ClassFileNewExpression(lineNumber, declaration.getSuperType(), bodyDeclaration, true);
                } else {
                    return new ClassFileNewExpression(lineNumber, ObjectType.TYPE_OBJECT, bodyDeclaration, true);
                }
            }
        }

        return new ClassFileNewExpression(lineNumber, objectType);
    }

    /*
     * Operators = { "+", "-", "*", "/", "%", "<<", ">>", ">>>" }
     * See "Additive Operators (+ and -) for Numeric Types": https://docs.oracle.com/javase/specs/jls/se7/html/jls-15.html#jls-15.18.2
     * See "Shift Operators":                                https://docs.oracle.com/javase/specs/jls/se7/html/jls-15.html#jls-15.19
     */
    private Expression newIntegerBinaryOperatorExpression(int lineNumber, Expression leftExpression, String operator, Expression rightExpression, int priority) {
        if (leftExpression.isLocalVariableReferenceExpression()) {
            AbstractLocalVariable leftVariable = ((ClassFileLocalVariableReferenceExpression)leftExpression).getLocalVariable();
            leftVariable.typeOnLeft(typeBounds, MAYBE_BYTE_TYPE);
        }

        if (rightExpression.isLocalVariableReferenceExpression()) {
            AbstractLocalVariable rightVariable = ((ClassFileLocalVariableReferenceExpression)rightExpression).getLocalVariable();
            rightVariable.typeOnLeft(typeBounds, MAYBE_BYTE_TYPE);
        }

        return new BinaryOperatorExpression(lineNumber, TYPE_INT, leftExpression, operator, rightExpression, priority);
    }

    /*
     * Operators = { "&", "|", "^" }
     * See "Binary Numeric Promotion": https://docs.oracle.com/javase/specs/jls/se7/html/jls-15.html#jls-15.22.1
     */
    private Expression newIntegerOrBooleanBinaryOperatorExpression(int lineNumber, Expression leftExpression, String operator, Expression rightExpression, int priority) {
        Type type = TYPE_INT;

        if (leftExpression.isLocalVariableReferenceExpression()) {
            AbstractLocalVariable leftVariable = ((ClassFileLocalVariableReferenceExpression)leftExpression).getLocalVariable();

            if (rightExpression.isLocalVariableReferenceExpression()) {
                AbstractLocalVariable rightVariable = ((ClassFileLocalVariableReferenceExpression)rightExpression).getLocalVariable();

                if (leftVariable.isAssignableFrom(typeBounds, TYPE_BOOLEAN) || rightVariable.isAssignableFrom(typeBounds, TYPE_BOOLEAN)) {
                    leftVariable.variableOnRight(typeBounds, rightVariable);
                    rightVariable.variableOnLeft(typeBounds, leftVariable);

                    if ((leftVariable.getType() == TYPE_BOOLEAN) || (rightVariable.getType() == TYPE_BOOLEAN)) {
                        type = TYPE_BOOLEAN;
                    }
                }
            } else {
                if (rightExpression.getType() == TYPE_BOOLEAN) {
                    leftVariable.typeOnRight(typeBounds, type = TYPE_BOOLEAN);
                }
            }
        } else if (rightExpression.isLocalVariableReferenceExpression()) {
            if (leftExpression.getType() == TYPE_BOOLEAN) {
                AbstractLocalVariable rightVariable = ((ClassFileLocalVariableReferenceExpression)rightExpression).getLocalVariable();
                rightVariable.typeOnRight(typeBounds, type = TYPE_BOOLEAN);
            }
        }

        if (type == TYPE_INT) {
            if (leftExpression.getType().isPrimitiveType() && rightExpression.getType().isPrimitiveType()) {
                int leftFlags = ((PrimitiveType)leftExpression.getType()).getFlags();
                int rightFlags = ((PrimitiveType)rightExpression.getType()).getFlags();
                boolean leftBoolean = (leftFlags & FLAG_BOOLEAN) != 0;
                boolean rightBoolean = (rightFlags & FLAG_BOOLEAN) != 0;
                int commonflags = leftFlags | rightFlags;

                if (!leftBoolean || !rightBoolean) {
                    commonflags &= ~FLAG_BOOLEAN;
                }

                type = PrimitiveTypeUtil.getPrimitiveTypeFromFlags(commonflags);

                if (type == null) {
                    type = TYPE_INT;
                }
            }
        }

        typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(leftExpression.getType(), rightExpression);
        typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(rightExpression.getType(), leftExpression);

        return new BinaryOperatorExpression(lineNumber, type, leftExpression, operator, rightExpression, priority);
    }

    /*
     * Operators = { "==", "!=" }
     * See "Numerical Equality Operators == and !=": https://docs.oracle.com/javase/specs/jls/se7/html/jls-15.html#jls-15.21.1
     */
    private Expression newIntegerOrBooleanComparisonOperatorExpression(int lineNumber, Expression leftExpression, String operator, Expression rightExpression, int priority) {
        if (leftExpression.isLocalVariableReferenceExpression()) {
            AbstractLocalVariable leftVariable = ((ClassFileLocalVariableReferenceExpression)leftExpression).getLocalVariable();

            if (rightExpression.isLocalVariableReferenceExpression()) {
                AbstractLocalVariable rightVariable = ((ClassFileLocalVariableReferenceExpression)rightExpression).getLocalVariable();

                if (leftVariable.isAssignableFrom(typeBounds, TYPE_BOOLEAN) || rightVariable.isAssignableFrom(typeBounds, TYPE_BOOLEAN)) {
                    leftVariable.variableOnRight(typeBounds, rightVariable);
                    rightVariable.variableOnLeft(typeBounds, leftVariable);
                }
            } else {
                if (rightExpression.getType() == TYPE_BOOLEAN) {
                    leftVariable.typeOnRight(typeBounds, TYPE_BOOLEAN);
                }
            }
        } else if (rightExpression.isLocalVariableReferenceExpression()) {
            if (leftExpression.getType() == TYPE_BOOLEAN) {
                AbstractLocalVariable rightVariable = ((ClassFileLocalVariableReferenceExpression)rightExpression).getLocalVariable();
                rightVariable.typeOnRight(typeBounds, TYPE_BOOLEAN);
            }
        }

        typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(leftExpression.getType(), rightExpression);
        typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(rightExpression.getType(), leftExpression);

        return new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, leftExpression, operator, rightExpression, priority);
    }

    /*
     * Operators = { "==", "!=" }
     * See "Numerical Equality Operators == and !=": https://docs.oracle.com/javase/specs/jls/se7/html/jls-15.html#jls-15.21.1
     */
    private Expression newIntegerComparisonOperatorExpression(int lineNumber, Expression leftExpression, String operator, Expression rightExpression, int priority) {
        if (leftExpression.isLocalVariableReferenceExpression()) {
            AbstractLocalVariable leftVariable = ((ClassFileLocalVariableReferenceExpression)leftExpression).getLocalVariable();
            leftVariable.typeOnLeft(typeBounds, MAYBE_BYTE_TYPE);
        }

        if (rightExpression.isLocalVariableReferenceExpression()) {
            AbstractLocalVariable rightVariable = ((ClassFileLocalVariableReferenceExpression)rightExpression).getLocalVariable();
            rightVariable.typeOnLeft(typeBounds, MAYBE_BYTE_TYPE);
        }

        typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(leftExpression.getType(), rightExpression);
        typeParametersToTypeArgumentsBinder.bindParameterTypesWithArgumentTypes(rightExpression.getType(), leftExpression);

        return new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, leftExpression, operator, rightExpression, priority);
    }

    private Expression newPreArithmeticOperatorExpression(int lineNumber, String operator, Expression expression) {
        reduceIntegerLocalVariableType(expression);
        return new PreOperatorExpression(lineNumber, operator, expression);
    }

    private Expression newPostArithmeticOperatorExpression(int lineNumber, Expression expression, String operator) {
        reduceIntegerLocalVariableType(expression);
        return new PostOperatorExpression(lineNumber, expression, operator);
    }

    private void reduceIntegerLocalVariableType(Expression expression) {
        if (expression.isLocalVariableReferenceExpression()) {
            ClassFileLocalVariableReferenceExpression lvre = (ClassFileLocalVariableReferenceExpression)expression;

            if (lvre.getLocalVariable().isPrimitiveLocalVariable()) {
                AbstractLocalVariable plv = lvre.getLocalVariable();
                if (plv.isAssignableFrom(typeBounds, MAYBE_BOOLEAN_TYPE)) {
                    plv.typeOnRight(typeBounds, MAYBE_BYTE_TYPE);
                }
            }
        }
    }

    /**
     * @return expression, 'this' or 'super'
     */
    private Expression getFieldInstanceReference(Expression expression, ObjectType ot, String name) {
        if ((bodyDeclaration.getFieldDeclarations() != null) && expression.isThisExpression()) {
            String internalName = expression.getType().getInternalName();

            if (!ot.getInternalName().equals(internalName)) {
                memberVisitor.init(name, null);
                for (ClassFileFieldDeclaration field : bodyDeclaration.getFieldDeclarations()) {
                    field.getFieldDeclarators().accept(memberVisitor);
                    if (memberVisitor.found()) {
                        return new SuperExpression(expression.getLineNumber(), expression.getType());
                    }
                }
            }
        }

        return expression;
    }

    /**
     * @return expression, 'this' or 'super'
     */
    private Expression getMethodInstanceReference(int opcode, ConstantMemberRef constantMemberRef, Expression expression, ObjectType ot, String name, String descriptor) {
        if ((opcode == 183) && constantMemberRef.isInterfaceMethodRef() && expression.isThisExpression() && // INVOKESPECIAL
            !ot.getInternalName().equals(expression.getType().getInternalName())) {
            // Default method of a super interface: 'A.super.m()'
            return new SuperExpression(expression.getLineNumber(), expression.getType(), ot);
        }

        if ((bodyDeclaration.getMethodDeclarations() != null) && expression.isThisExpression()) {
            String internalName = expression.getType().getInternalName();

            if (!ot.getInternalName().equals(internalName)) {
                memberVisitor.init(name, descriptor);

                for (ClassFileConstructorOrMethodDeclaration member : bodyDeclaration.getMethodDeclarations()) {
                    member.accept(memberVisitor);
                    if (memberVisitor.found()) {
                        return new SuperExpression(expression.getLineNumber(), expression.getType());
                    }
                }
            }
        }

        return expression;
    }

    /** Member of an anonymous class accessed through a local variable: the variable must be declared with 'var'. */
    private static void checkAnonymousTypeReceiver(Expression expression, ObjectType ot) {
        if (expression.isLocalVariableReferenceExpression() && (ot.getQualifiedName() == null) && (ot.getName() == null)) {
            ((ClassFileLocalVariableReferenceExpression)expression).getLocalVariable().setAnonymousTypeRequired(true);
        }
    }

    /** 'o instanceof R(...)' */
    private Expression makeRecordPatternInstanceOf(int lineNumber, int offset, RecordPatternMaker.Region region) {
        AbstractLocalVariable root = localVariableMaker.getLocalVariable(region.getRootIndex(), offset);
        Expression pattern = makeRecordPattern(lineNumber, region.getRoot());
        return new InstanceOfExpression(lineNumber, new ClassFileLocalVariableReferenceExpression(lineNumber, offset, root), pattern.getType(), pattern);
    }

    private Expression makeRecordPattern(int lineNumber, RecordPatternMaker.Value value) {
        if (value.isRecord()) {
            DefaultList<Expression> components = new DefaultList<>(value.getComponents().size());

            for (RecordPatternMaker.Value component : value.getComponents()) {
                components.add(makeRecordPattern(lineNumber, component));
            }

            return new RecordPatternExpression(lineNumber, typeMaker.makeFromInternalTypeName(value.getType()), components);
        }

        if (value.getBindingIndex() == -1) {
            // Unnamed pattern: '_' or 'Type _'
            return new TypePatternExpression(lineNumber, (value.getType() == null) ? null : typeMaker.makeFromInternalTypeName(value.getType()), null);
        }

        // 'Type name': declared by the pattern
        String descriptor = value.getAccessorDescriptor();
        Type type = (value.getType() == null) ? typeMaker.makeFromSignature(descriptor.substring(descriptor.indexOf(')') + 1)) : typeMaker.makeFromDescriptorOrInternalTypeName(value.getType());
        AbstractLocalVariable binding = localVariableMaker.getLocalVariableInAssignment(typeBounds, value.getBindingIndex(), value.getBindingOffset(), type);
        binding.setDeclared(true);
        return new TypePatternExpression(lineNumber, binding.getType(), new ClassFileLocalVariableReferenceExpression(lineNumber, value.getBindingOffset(), binding));
    }

    private static void checkStack(DefaultStack<Expression> stack, byte[] code, int offset) {
        if ((stack.size() > 1) && (offset < code.length)) {
            int opcode = code[offset+1] & 255;

            if ((opcode == 87) || (opcode == 176)) { // POP || ARETURN
                // Duplicate last expression
                Expression condition = stack.pop();
                stack.push(stack.peek());
                stack.push(condition);
            }
        }
    }

    public static boolean isAssertCondition(String internalTypeName, BasicBlock basicBlock) {
        ControlFlowGraph cfg = basicBlock.getControlFlowGraph();
        int offset = basicBlock.getFromOffset();
        int toOffset = basicBlock.getToOffset();

        if (offset + 3 > toOffset)
            return false;

        Method method = cfg.getMethod();
        byte[] code = method.<AttributeCode>getAttribute("Code").getCode();
        int opcode = code[offset] & 255;

        if (opcode != 178) // GETSTATIC
            return false;

        ConstantPool constants = method.getConstants();
        ConstantMemberRef constantMemberRef = constants.getConstant( ((code[++offset] & 255) << 8) | (code[++offset] & 255) );
        ConstantNameAndType constantNameAndType = constants.getConstant(constantMemberRef.getNameAndTypeIndex());
        String name = constants.getConstantUtf8(constantNameAndType.getNameIndex());

        if (! "$assertionsDisabled".equals(name))
            return false;

        String descriptor = constants.getConstantUtf8(constantNameAndType.getDescriptorIndex());

        if (! "Z".equals(descriptor))
            return false;

        String typeName = constants.getConstantTypeName(constantMemberRef.getClassIndex());

        return internalTypeName.equals(typeName);
    }

    public static int getExceptionLocalVariableIndex(BasicBlock basicBlock) {
        ControlFlowGraph cfg = basicBlock.getControlFlowGraph();
        int offset = basicBlock.getFromOffset();
        int toOffset = basicBlock.getToOffset();

        if (offset + 1 > toOffset) {
            assert false;
            return -1;
        }

        Method method = cfg.getMethod();
        byte[] code = method.<AttributeCode>getAttribute("Code").getCode();
        int opcode = code[offset] & 255;

        switch (opcode) {
            case 58: // ASTORE
                return code[++offset] & 255;
            case 75: case 76: case 77: case 78: // ASTORE_0 ... ASTORE_3
                return opcode - 75;
            case 87: case 88: // POP, POP2
                return -1;
            default:
                assert false;
                return -1;
        }
    }

    private static class MemberVisitor extends AbstractJavaSyntaxVisitor {
        protected String name;
        protected String descriptor;
        protected boolean found;

        public void init(String name, String descriptor) {
            this.name = name;
            this.descriptor = descriptor;
            this.found = false;
        }

        public boolean found() {
            return found;
        }

        @Override
        public void visit(FieldDeclarator declaration) {
            found |= declaration.getName().equals(name);
        }

        @Override
        public void visit(MethodDeclaration declaration) {
            found |= declaration.getName().equals(name) && declaration.getDescriptor().equals(descriptor);
        }
    }

    private static class LambdaParameterNamesVisitor extends AbstractNopDeclarationVisitor {
        protected DefaultList<String> names;

        public void init() {
            names = new DefaultList<>();
        }

        public List<String> getNames() {
            return names;
        }

        @Override public void visit(FormalParameter declaration) {
            names.add(declaration.getName());
        }

        @Override
        @SuppressWarnings("unchecked")
        public void visit(FormalParameters declarations) {
            Iterator<FormalParameter> iterator = declarations.iterator();

            while (iterator.hasNext()) {
                iterator.next().accept(this);
            }
        }
    }

    private static class JsrReturnAddressExpression extends NullExpression {
        public JsrReturnAddressExpression() {
            super(TYPE_VOID);
        }

        @Override
        public String toString() {
            return "JsrReturnAddressExpression{}";
        }
    }

    private Type makeFieldType(String internalTypeName, String fieldName, String descriptor) {
        Type type = typeMaker.makeFieldType(internalTypeName, fieldName, descriptor);

        if (!genericTypesSupported) {
            eraseTypeArgumentVisitor.init();
            type.accept(eraseTypeArgumentVisitor);
            type = (Type)eraseTypeArgumentVisitor.getBaseType();
        }

        return type;
    }

    private TypeMaker.MethodTypes makeMethodTypes(String internalTypeName, String methodName, String descriptor) {
        TypeMaker.MethodTypes methodTypes = typeMaker.makeMethodTypes(internalTypeName, methodName, descriptor);

        if (!genericTypesSupported) {
            TypeMaker.MethodTypes mt = new TypeMaker.MethodTypes();

            if (methodTypes.parameterTypes != null) {
                eraseTypeArgumentVisitor.init();
                methodTypes.parameterTypes.accept(eraseTypeArgumentVisitor);
                mt.parameterTypes = eraseTypeArgumentVisitor.getBaseType();
            }

            eraseTypeArgumentVisitor.init();
            methodTypes.returnedType.accept(eraseTypeArgumentVisitor);
            mt.returnedType = (Type)eraseTypeArgumentVisitor.getBaseType();

            if (methodTypes.exceptionTypes != null) {
                eraseTypeArgumentVisitor.init();
                methodTypes.exceptionTypes.accept(eraseTypeArgumentVisitor);
                mt.exceptionTypes = eraseTypeArgumentVisitor.getBaseType();
            }

            methodTypes = mt;
        }

        return methodTypes;
    }

    protected static Expression keepFloatingPointOperation(Expression expression1, Expression expression2) {
        // '(double)i / l': if both operands are implicit widenings of integral values, the source would compute an
        // integral operation => set the left cast as explicit
        if (isImplicitIntegralWidening(expression1) && isImplicitIntegralWidening(expression2)) {
            ((CastExpression)expression1).setExplicit(true);
        }

        return expression1;
    }

    private static boolean isImplicitIntegralWidening(Expression expression) {
        if (expression.isCastExpression() && !((CastExpression)expression).isExplicit()) {
            Type type = expression.getExpression().getType();
            return type.isPrimitiveType() && (type != TYPE_FLOAT) && (type != TYPE_DOUBLE) && (type != TYPE_BOOLEAN);
        }

        return false;
    }

    protected static Expression forceExplicitCastExpression(Expression expression) {
        // In case of downcasting, set all cast expression children as explicit to prevent missing castings
        Expression exp = expression;

        while (exp.isCastExpression()) {
            CastExpression ce = (CastExpression)exp;
            ce.setExplicit(true);
            exp = ce.getExpression();
        }

        return expression;
    }
}
