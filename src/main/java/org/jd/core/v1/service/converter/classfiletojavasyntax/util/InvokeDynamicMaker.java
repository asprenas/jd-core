/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.util;

import org.jd.core.v1.model.classfile.ConstantPool;
import org.jd.core.v1.model.classfile.attribute.BootstrapMethod;
import org.jd.core.v1.model.classfile.constant.*;
import org.jd.core.v1.model.javasyntax.expression.*;
import org.jd.core.v1.model.javasyntax.type.BaseType;
import org.jd.core.v1.model.javasyntax.type.ObjectType;
import org.jd.core.v1.model.javasyntax.type.PrimitiveType;
import org.jd.core.v1.model.javasyntax.type.Type;
import org.jd.core.v1.model.javasyntax.type.Types;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.expression.ClassFileMethodInvocationExpression;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.expression.ClassFileSwitchIndexExpression;

import java.util.ArrayList;
import java.util.List;

import static org.jd.core.v1.model.javasyntax.type.ObjectType.TYPE_OBJECT;
import static org.jd.core.v1.model.javasyntax.type.ObjectType.TYPE_STRING;
import static org.jd.core.v1.model.javasyntax.type.PrimitiveType.TYPE_BOOLEAN;
import static org.jd.core.v1.model.javasyntax.type.PrimitiveType.TYPE_INT;

/**
 * Java expressions with the same behavior as the 'invokedynamic' call sites created with the Java 16+ runtime
 * bootstrap methods (records and pattern matching 'switch'):
 * <ul>
 *     <li>java/lang/runtime/ObjectMethods.bootstrap: record toString(), hashCode() and equals(Object)</li>
 *     <li>java/lang/runtime/SwitchBootstraps.typeSwitch / enumSwitch: index of the first matching case label</li>
 * </ul>
 * Each method throws IllegalStateException when the call site is not understood: the caller falls back to
 * byte code output instead of printing wrong source.
 */
public class InvokeDynamicMaker {
    public static final String LAMBDA_METAFACTORY    = "java/lang/invoke/LambdaMetafactory";
    public static final String STRING_CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory";
    public static final String OBJECT_METHODS        = "java/lang/runtime/ObjectMethods";
    public static final String SWITCH_BOOTSTRAPS     = "java/lang/runtime/SwitchBootstraps";

    protected final TypeMaker typeMaker;
    protected final ConstantPool constants;
    protected final BootstrapMethod[] bootstrapMethods;

    public InvokeDynamicMaker(TypeMaker typeMaker, ConstantPool constants, BootstrapMethod[] bootstrapMethods) {
        this.typeMaker = typeMaker;
        this.constants = constants;
        this.bootstrapMethods = bootstrapMethods;
    }

    /** Returns {owner internal type name, method name} of the bootstrap method. */
    public String[] getBootstrapMethod(BootstrapMethod bootstrapMethod) {
        ConstantMethodHandle cmh = constants.getConstant(bootstrapMethod.getBootstrapMethodRef());
        ConstantMemberRef cmr = constants.getConstant(cmh.getReferenceIndex());
        ConstantNameAndType cnat = constants.getConstant(cmr.getNameAndTypeIndex());
        return new String[] { constants.getConstantTypeName(cmr.getClassIndex()), constants.getConstantUtf8(cnat.getNameIndex()) };
    }

    // --- ObjectMethods --- //

    public Expression makeObjectMethod(int lineNumber, String indyMethodName, BootstrapMethod bootstrapMethod, BaseExpression indyParameters) {
        int[] arguments = bootstrapMethod.getBootstrapArguments();
        ObjectType recordType = typeMaker.makeFromInternalTypeName(constants.getConstantTypeName(arguments[0]));
        String names = constants.getConstantString(arguments[1]);
        int count = arguments.length - 2;
        String[] fieldNames = new String[count];
        String[] fieldDescriptors = new String[count];

        for (int i = 0; i < count; i++) {
            ConstantMethodHandle getter = constants.getConstant(arguments[i + 2]);
            ConstantMemberRef cmr = constants.getConstant(getter.getReferenceIndex());
            ConstantNameAndType cnat = constants.getConstant(cmr.getNameAndTypeIndex());
            fieldNames[i] = constants.getConstantUtf8(cnat.getNameIndex());
            fieldDescriptors[i] = constants.getConstantUtf8(cnat.getDescriptorIndex());
        }

        if (indyParameters == null) {
            throw new IllegalStateException("ObjectMethods : unexpected parameters");
        }

        Expression receiver = indyParameters.getFirst();

        switch (indyMethodName) {
            case "toString":
                return makeRecordToString(lineNumber, recordType, receiver, names.isEmpty() ? new String[0] : names.split(";"), fieldNames, fieldDescriptors);
            case "hashCode":
                return makeRecordHashCode(lineNumber, recordType, receiver, fieldNames, fieldDescriptors);
            case "equals":
                if (!indyParameters.isList() || (indyParameters.size() != 2)) {
                    throw new IllegalStateException("ObjectMethods.equals : unexpected parameters");
                }
                return makeRecordEquals(lineNumber, recordType, receiver, indyParameters.getList().get(1), fieldNames, fieldDescriptors);
            default:
                throw new IllegalStateException("ObjectMethods : unexpected method " + indyMethodName);
        }
    }

    /** "Point[x=" + this.x + ", y=" + this.y + "]" */
    protected Expression makeRecordToString(int lineNumber, ObjectType recordType, Expression receiver, String[] names, String[] fieldNames, String[] fieldDescriptors) {
        StringBuilder text = new StringBuilder(simpleName(recordType.getInternalName())).append('[');
        Expression expression = null;

        for (int i = 0; i < fieldNames.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append((i < names.length) ? names[i] : fieldNames[i]).append('=');
            expression = concat(lineNumber, expression, new StringConstantExpression(lineNumber, text.toString()));
            expression = concat(lineNumber, expression, field(lineNumber, recordType, receiver, fieldNames[i], fieldDescriptors[i]));
            text.setLength(0);
        }

        text.append(']');
        return concat(lineNumber, expression, new StringConstantExpression(lineNumber, text.toString()));
    }

    /** result = result * 31 + hash(component), starting from 0 */
    protected Expression makeRecordHashCode(int lineNumber, ObjectType recordType, Expression receiver, String[] fieldNames, String[] fieldDescriptors) {
        Expression result = null;

        for (int i = 0; i < fieldNames.length; i++) {
            String descriptor = fieldDescriptors[i];
            Expression field = field(lineNumber, recordType, receiver, fieldNames[i], descriptor);
            Expression hash;

            if (isPrimitive(descriptor)) {
                hash = staticCall(lineNumber, TYPE_INT, wrapperInternalTypeName(descriptor.charAt(0)), "hashCode", "(" + descriptor + ")I",
                    typeMaker.makeFromSignature(descriptor), field);
            } else {
                hash = staticCall(lineNumber, TYPE_INT, "java/util/Objects", "hashCode", "(Ljava/lang/Object;)I", TYPE_OBJECT, field);
            }

            if (result == null) {
                result = hash;
            } else {
                Expression product = new BinaryOperatorExpression(lineNumber, TYPE_INT, result, "*", new IntegerConstantExpression(lineNumber, TYPE_INT, 31), 5);
                result = new BinaryOperatorExpression(lineNumber, TYPE_INT, product, "+", hash, 6);
            }
        }

        return (result == null) ? new IntegerConstantExpression(lineNumber, TYPE_INT, 0) : result;
    }

    /** other instanceof Point && this.x == ((Point)other).x && Objects.equals(this.s, ((Point)other).s) ... */
    protected Expression makeRecordEquals(int lineNumber, ObjectType recordType, Expression receiver, Expression other, String[] fieldNames, String[] fieldDescriptors) {
        if (!other.isLocalVariableReferenceExpression()) {
            // 'other' is evaluated several times
            throw new IllegalStateException("ObjectMethods.equals : unexpected parameter");
        }

        Expression result = new InstanceOfExpression(lineNumber, other, recordType);

        for (int i = 0; i < fieldNames.length; i++) {
            String descriptor = fieldDescriptors[i];
            Expression left = field(lineNumber, recordType, receiver, fieldNames[i], descriptor);
            Expression right = field(lineNumber, recordType, new CastExpression(lineNumber, recordType, other), fieldNames[i], descriptor);
            Expression test;

            switch (descriptor.charAt(0)) {
                case 'F':
                    test = compareIsZero(lineNumber, "java/lang/Float", "(FF)I", PrimitiveType.TYPE_FLOAT, left, right);
                    break;
                case 'D':
                    test = compareIsZero(lineNumber, "java/lang/Double", "(DD)I", PrimitiveType.TYPE_DOUBLE, left, right);
                    break;
                case 'L': case '[':
                    test = staticCall(lineNumber, TYPE_BOOLEAN, "java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                        new Types(TYPE_OBJECT, TYPE_OBJECT), left, right);
                    break;
                default:
                    test = new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, left, "==", right, 8);
                    break;
            }

            result = new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, result, "&&", test, 13);
        }

        return result;
    }

    protected Expression compareIsZero(int lineNumber, String owner, String descriptor, Type type, Expression left, Expression right) {
        Expression compare = staticCall(lineNumber, TYPE_INT, owner, "compare", descriptor, new Types(type, type), left, right);
        return new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, compare, "==", new IntegerConstantExpression(lineNumber, TYPE_INT, 0), 8);
    }

    // --- SwitchBootstraps --- //

    /**
     * typeSwitch(selector, restart) and enumSwitch(selector, restart) return -1 for null, the index of the first
     * label at or after 'restart' matching the selector, or the number of labels:
     *   selector == null ? -1 : (restart <= 0 && match0) ? 0 : (restart <= 1 && match1) ? 1 : ... : n
     */
    public Expression makeSwitchIndex(int lineNumber, String indyMethodName, BootstrapMethod bootstrapMethod, BaseExpression indyParameters, String indyDescriptor) {
        if ((indyParameters == null) || !indyParameters.isList() || (indyParameters.size() != 2)) {
            throw new IllegalStateException("SwitchBootstraps : unexpected parameters");
        }

        Expression selector = indyParameters.getList().get(0);
        Expression restart = indyParameters.getList().get(1);

        if (!selector.isLocalVariableReferenceExpression() || !(restart.isLocalVariableReferenceExpression() || restart.isIntegerConstantExpression())) {
            // Both are evaluated several times
            throw new IllegalStateException("SwitchBootstraps : unexpected parameters");
        }

        ObjectType enumType = null;

        if ("enumSwitch".equals(indyMethodName)) {
            // (LEnum;I)I
            enumType = typeMaker.makeFromDescriptor(indyDescriptor.substring(1, indyDescriptor.indexOf(';') + 1));
        } else if (!"typeSwitch".equals(indyMethodName)) {
            throw new IllegalStateException("SwitchBootstraps : unexpected method " + indyMethodName);
        }

        int[] arguments = bootstrapMethod.getBootstrapArguments();
        Expression result = new IntegerConstantExpression(lineNumber, TYPE_INT, arguments.length);
        List<ClassFileSwitchIndexExpression.Label> labels = new ArrayList<>(arguments.length);

        for (int argument : arguments) {
            labels.add(makeLabel(lineNumber, argument, enumType));
        }

        for (int i = arguments.length - 1; i >= 0; i--) {
            Expression match = makeLabelMatch(lineNumber, selector, arguments[i], enumType);
            Expression restartTest = new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, restart, "<=", new IntegerConstantExpression(lineNumber, TYPE_INT, i), 7);
            Expression condition = new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, restartTest, "&&", match, 13);
            result = new TernaryOperatorExpression(lineNumber, TYPE_INT, condition, new IntegerConstantExpression(lineNumber, TYPE_INT, i), result);
        }

        Expression isNull = new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, selector, "==", new NullExpression(lineNumber, TYPE_OBJECT), 8);
        TernaryOperatorExpression index = new TernaryOperatorExpression(lineNumber, TYPE_INT, isNull, new IntegerConstantExpression(lineNumber, TYPE_INT, -1), result);
        return new ClassFileSwitchIndexExpression(index, selector, restart, labels);
    }

    /** The source form of a label: a type pattern, or a constant ('case "a"', 'case 1', 'case Coin.HEADS') */
    protected ClassFileSwitchIndexExpression.Label makeLabel(int lineNumber, int labelIndex, ObjectType enumType) {
        Constant label = constants.getConstant(labelIndex);

        if (label instanceof ConstantClass) {
            return new ClassFileSwitchIndexExpression.Label(typeMaker.makeFromDescriptorOrInternalTypeName(constants.getConstantTypeName(labelIndex)), null);
        }
        if (label instanceof ConstantString) {
            String value = constants.getConstantString(labelIndex);
            if (enumType != null) {
                return new ClassFileSwitchIndexExpression.Label(enumType, enumConstant(lineNumber, enumType, value));
            }
            return new ClassFileSwitchIndexExpression.Label(TYPE_STRING, new StringConstantExpression(lineNumber, value));
        }
        if (label instanceof ConstantInteger) {
            return new ClassFileSwitchIndexExpression.Label(null, new IntegerConstantExpression(lineNumber, TYPE_INT, ((ConstantInteger)label).getValue()));
        }
        if (label instanceof ConstantDynamic) {
            String[] enumConstant = resolveEnumDesc((ConstantDynamic)label);
            ObjectType type = typeMaker.makeFromInternalTypeName(enumConstant[0]);
            return new ClassFileSwitchIndexExpression.Label(type, enumConstant(lineNumber, type, enumConstant[1]));
        }

        throw new IllegalStateException("SwitchBootstraps : unexpected label " + label);
    }

    protected Expression makeLabelMatch(int lineNumber, Expression selector, int labelIndex, ObjectType enumType) {
        Constant label = constants.getConstant(labelIndex);

        if (label instanceof ConstantClass) {
            // Type pattern
            ObjectType type = typeMaker.makeFromDescriptorOrInternalTypeName(constants.getConstantTypeName(labelIndex));
            return new InstanceOfExpression(lineNumber, selector, type);
        }

        if (label instanceof ConstantString) {
            String value = constants.getConstantString(labelIndex);

            if (enumType != null) {
                // enumSwitch: enum constant name
                return new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, selector, "==", enumConstant(lineNumber, enumType, value), 8);
            }
            // "value".equals(selector)
            return new ClassFileMethodInvocationExpression(lineNumber, null, TYPE_BOOLEAN, new StringConstantExpression(lineNumber, value),
                "java/lang/String", "equals", "(Ljava/lang/Object;)Z", TYPE_OBJECT, selector);
        }

        if (label instanceof ConstantInteger) {
            // selector instanceof Integer && ((Integer)selector).intValue() == value
            ObjectType integerType = typeMaker.makeFromInternalTypeName("java/lang/Integer");
            Expression instanceOf = new InstanceOfExpression(lineNumber, selector, integerType);
            Expression intValue = new ClassFileMethodInvocationExpression(lineNumber, null, TYPE_INT, new CastExpression(lineNumber, integerType, selector),
                "java/lang/Integer", "intValue", "()I", null, null);
            Expression equals = new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, intValue, "==", new IntegerConstantExpression(lineNumber, TYPE_INT, ((ConstantInteger)label).getValue()), 8);
            return new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, instanceOf, "&&", equals, 13);
        }

        if (label instanceof ConstantDynamic) {
            // Enum constant: ConstantBootstraps.invoke(EnumDesc.of(ClassDesc.of("p.Outer$Enum"), "NAME"))
            String[] enumConstant = resolveEnumDesc((ConstantDynamic)label);
            ObjectType type = typeMaker.makeFromInternalTypeName(enumConstant[0]);
            return new BinaryOperatorExpression(lineNumber, TYPE_BOOLEAN, selector, "==", enumConstant(lineNumber, type, enumConstant[1]), 8);
        }

        throw new IllegalStateException("SwitchBootstraps : unexpected label " + label);
    }

    /** Returns {enum internal type name, constant name} of a CONSTANT_Dynamic created with EnumDesc.of(ClassDesc.of(...), name). */
    protected String[] resolveEnumDesc(ConstantDynamic constant) {
        BootstrapMethod bootstrapMethod = bootstrapMethods[constant.getClassIndex()];
        int[] arguments = bootstrapMethod.getBootstrapArguments();

        if (!"invoke".equals(getBootstrapMethod(bootstrapMethod)[1]) || (arguments.length != 3) || !isMethodHandle(arguments[0], "java/lang/Enum$EnumDesc", "of")) {
            throw new IllegalStateException("SwitchBootstraps : unexpected enum label");
        }

        Constant classDesc = constants.getConstant(arguments[1]);

        if (!(classDesc instanceof ConstantDynamic)) {
            throw new IllegalStateException("SwitchBootstraps : unexpected enum label");
        }

        int[] classDescArguments = bootstrapMethods[((ConstantDynamic)classDesc).getClassIndex()].getBootstrapArguments();

        if ((classDescArguments.length != 2) || !isMethodHandle(classDescArguments[0], "java/lang/constant/ClassDesc", "of")) {
            throw new IllegalStateException("SwitchBootstraps : unexpected enum label");
        }

        String binaryName = constants.getConstantString(classDescArguments[1]);
        return new String[] { binaryName.replace('.', '/'), constants.getConstantString(arguments[2]) };
    }

    protected boolean isMethodHandle(int index, String owner, String name) {
        Constant constant = constants.getConstant(index);

        if (!(constant instanceof ConstantMethodHandle)) {
            return false;
        }

        ConstantMemberRef cmr = constants.getConstant(((ConstantMethodHandle)constant).getReferenceIndex());
        ConstantNameAndType cnat = constants.getConstant(cmr.getNameAndTypeIndex());
        return owner.equals(constants.getConstantTypeName(cmr.getClassIndex())) && name.equals(constants.getConstantUtf8(cnat.getNameIndex()));
    }

    // --- Utilities --- //

    protected Expression field(int lineNumber, ObjectType owner, Expression receiver, String name, String descriptor) {
        return new FieldReferenceExpression(lineNumber, typeMaker.makeFromSignature(descriptor), receiver, owner.getInternalName(), name, descriptor);
    }

    /** Qualified enum constant 'Enum.NAME' (a 'case' label prints the simple name, an expression must not) */
    protected static Expression enumConstant(int lineNumber, ObjectType type, String name) {
        return new FieldReferenceExpression(lineNumber, type, new ObjectTypeReferenceExpression(lineNumber, type), type.getInternalName(), name, type.getDescriptor());
    }

    protected static Expression concat(int lineNumber, Expression left, Expression right) {
        return (left == null) ? right : new BinaryOperatorExpression(lineNumber, TYPE_STRING, left, "+", right, 6);
    }

    protected Expression staticCall(int lineNumber, Type type, String owner, String name, String descriptor, BaseType parameterTypes, Expression... parameters) {
        ObjectType ot = typeMaker.makeFromInternalTypeName(owner);
        BaseExpression baseParameters;

        if (parameters.length == 1) {
            baseParameters = parameters[0];
        } else {
            Expressions list = new Expressions();
            for (Expression parameter : parameters) {
                list.add(parameter);
            }
            baseParameters = list;
        }

        return new ClassFileMethodInvocationExpression(lineNumber, null, type, new ObjectTypeReferenceExpression(lineNumber, ot), owner, name, descriptor, parameterTypes, baseParameters);
    }

    protected static boolean isPrimitive(String descriptor) {
        return (descriptor.length() == 1) && (descriptor.charAt(0) != 'L') && (descriptor.charAt(0) != '[');
    }

    protected static String simpleName(String internalName) {
        int index = Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$'));
        return internalName.substring(index + 1);
    }

    protected static String wrapperInternalTypeName(char descriptor) {
        switch (descriptor) {
            case 'Z': return "java/lang/Boolean";
            case 'B': return "java/lang/Byte";
            case 'C': return "java/lang/Character";
            case 'S': return "java/lang/Short";
            case 'I': return "java/lang/Integer";
            case 'J': return "java/lang/Long";
            case 'F': return "java/lang/Float";
            case 'D': return "java/lang/Double";
            default: throw new IllegalStateException("unexpected descriptor " + descriptor);
        }
    }
}
