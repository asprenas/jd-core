/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.visitor;

import org.jd.core.v1.model.classfile.ClassFile;
import org.jd.core.v1.model.classfile.ConstantPool;
import org.jd.core.v1.model.classfile.Method;
import org.jd.core.v1.model.classfile.attribute.AttributeBootstrapMethods;
import org.jd.core.v1.model.classfile.attribute.AttributeCode;
import org.jd.core.v1.model.classfile.attribute.AttributeRecord;
import org.jd.core.v1.model.classfile.constant.ConstantMemberRef;
import org.jd.core.v1.model.javasyntax.declaration.*;
import org.jd.core.v1.model.javasyntax.expression.Expression;
import org.jd.core.v1.model.javasyntax.statement.Statement;
import org.jd.core.v1.model.javasyntax.statement.Statements;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.*;
import org.jd.core.v1.service.converter.classfiletojavasyntax.util.InvokeDynamicMaker;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.jd.core.v1.model.javasyntax.declaration.Declaration.*;

/**
 * Java 16+ records: hides the members javac generates for the record components so the declaration can be
 * printed as 'record Name(components) { ... }'.
 * <ul>
 *     <li>the private final field of each component</li>
 *     <li>the accessors returning the field ('aload_0, getfield, return')</li>
 *     <li>toString(), hashCode() and equals(Object) built with ObjectMethods.bootstrap</li>
 *     <li>the canonical constructor when it only assigns the fields; when other statements come first, the
 *         assignments are removed and the constructor becomes a compact canonical constructor</li>
 * </ul>
 * Any member javac would not generate (explicit accessor, explicit canonical constructor) is kept.
 */
public class UpdateRecordDeclarationVisitor {
    public void visit(ClassFileClassDeclaration declaration) {
        ClassFileBodyDeclaration bodyDeclaration = (ClassFileBodyDeclaration)declaration.getBodyDeclaration();
        ClassFile classFile = bodyDeclaration.getClassFile();
        AttributeRecord attributeRecord = classFile.getAttribute("Record");

        if ((attributeRecord == null) || !"java/lang/Record".equals(classFile.getSuperTypeName())) {
            return;
        }

        AttributeRecord.RecordComponent[] components = attributeRecord.getComponents();
        Set<String> componentNames = new HashSet<>();
        StringBuilder canonicalDescriptor = new StringBuilder("(");

        for (AttributeRecord.RecordComponent component : components) {
            componentNames.add(component.getName());
            canonicalDescriptor.append(component.getDescriptor());
        }
        canonicalDescriptor.append(")V");

        BaseFormalParameter recordComponents = null;
        List<ClassFileConstructorOrMethodDeclaration> methods = bodyDeclaration.getMethodDeclarations();

        if (methods != null) {
            for (ClassFileConstructorOrMethodDeclaration method : methods) {
                Method m = method.getMethod();

                if (method instanceof ClassFileConstructorDeclaration) {
                    if (canonicalDescriptor.toString().equals(m.getDescriptor())) {
                        ClassFileConstructorDeclaration constructor = (ClassFileConstructorDeclaration)method;
                        recordComponents = constructor.getFormalParameters();
                        updateCanonicalConstructor(constructor, components);
                    }
                } else if (isGeneratedObjectMethod(classFile, m) || isGeneratedAccessor(classFile, m, componentNames)) {
                    method.setFlags(method.getFlags() | FLAG_SYNTHETIC);
                }
            }
        }

        if (recordComponents == null) {
            // Without the canonical constructor, the header cannot be printed with the parameter names
            if (components.length > 0) {
                return;
            }
            recordComponents = new FormalParameters();
        }

        // Hide the component fields
        if (bodyDeclaration.getFieldDeclarations() != null) {
            for (ClassFileFieldDeclaration field : bodyDeclaration.getFieldDeclarations()) {
                if (((field.getFlags() & FLAG_STATIC) == 0) && allComponents(field.getFieldDeclarators(), componentNames)) {
                    field.setFlags(field.getFlags() | FLAG_SYNTHETIC);
                }
            }
        }

        declaration.setRecordComponents(recordComponents);
        // A record is implicitly final, a nested record implicitly static
        declaration.setFlags(declaration.getFlags() & ~(FLAG_FINAL|FLAG_STATIC));
    }

    protected void updateCanonicalConstructor(ClassFileConstructorDeclaration constructor, AttributeRecord.RecordComponent[] components) {
        if ((constructor.getStatements() == null) || !constructor.getStatements().isList()) {
            if (components.length == 0) {
                constructor.setFlags(constructor.getFlags() | FLAG_SYNTHETIC);
            }
            return;
        }

        Statements statements = (Statements)constructor.getStatements();
        int size = statements.size();
        int count = components.length;

        if (size < count) {
            return;
        }

        // The last statements must be 'this.c = c;' in component order
        for (int i = 0; i < count; i++) {
            if (!isComponentAssignment(statements.get(size - count + i), components[i].getName())) {
                return;
            }
        }

        if (size == count) {
            constructor.setFlags(constructor.getFlags() | FLAG_SYNTHETIC);
        } else {
            statements.subList(size - count, size).clear();
            constructor.setCompactCanonical(true);
        }
    }

    protected static boolean isComponentAssignment(Statement statement, String name) {
        if (!statement.isExpressionStatement()) {
            return false;
        }

        Expression expression = statement.getExpression();

        if (!expression.isBinaryOperatorExpression() || !"=".equals(expression.getOperator())) {
            return false;
        }

        Expression left = expression.getLeftExpression();
        Expression right = expression.getRightExpression();

        return left.isFieldReferenceExpression() && name.equals(left.getName()) &&
               ((left.getExpression() == null) || left.getExpression().isThisExpression()) &&
               right.isLocalVariableReferenceExpression() && name.equals(right.getName());
    }

    protected static boolean allComponents(BaseFieldDeclarator declarators, Set<String> componentNames) {
        if (declarators.isList()) {
            for (FieldDeclarator declarator : declarators.getList()) {
                if (!componentNames.contains(declarator.getName())) {
                    return false;
                }
            }
            return true;
        }

        return componentNames.contains(declarators.getFirst().getName());
    }

    /** aload_0, (aload_1,) invokedynamic ObjectMethods.bootstrap, return */
    protected static boolean isGeneratedObjectMethod(ClassFile classFile, Method method) {
        String key = method.getName() + method.getDescriptor();
        int indyOffset;

        switch (key) {
            case "toString()Ljava/lang/String;": case "hashCode()I":
                indyOffset = 1;
                break;
            case "equals(Ljava/lang/Object;)Z":
                indyOffset = 2;
                break;
            default:
                return false;
        }

        byte[] code = getCode(method);

        if ((code == null) || (code.length != indyOffset + 6) || ((code[indyOffset] & 255) != 186)) {
            return false;
        }

        AttributeBootstrapMethods bootstrapMethods = classFile.getAttribute("BootstrapMethods");

        if (bootstrapMethods == null) {
            return false;
        }

        ConstantPool constants = method.getConstants();
        int index = ((code[indyOffset + 1] & 255) << 8) | (code[indyOffset + 2] & 255);
        ConstantMemberRef indy = constants.getConstant(index);
        InvokeDynamicMaker maker = new InvokeDynamicMaker(null, constants, bootstrapMethods.getBootstrapMethods());

        return InvokeDynamicMaker.OBJECT_METHODS.equals(maker.getBootstrapMethod(bootstrapMethods.getBootstrapMethods()[indy.getClassIndex()])[0]);
    }

    /** aload_0, getfield component, return */
    protected static boolean isGeneratedAccessor(ClassFile classFile, Method method, Set<String> componentNames) {
        if (!componentNames.contains(method.getName()) || !method.getDescriptor().startsWith("()")) {
            return false;
        }

        byte[] code = getCode(method);

        if ((code == null) || (code.length != 5) || ((code[0] & 255) != 42) || ((code[1] & 255) != 180)) {
            return false;
        }

        ConstantPool constants = method.getConstants();
        ConstantMemberRef field = constants.getConstant(((code[2] & 255) << 8) | (code[3] & 255));
        String fieldName = constants.getConstantUtf8(((org.jd.core.v1.model.classfile.constant.ConstantNameAndType)constants.getConstant(field.getNameAndTypeIndex())).getNameIndex());

        return method.getName().equals(fieldName) && classFile.getInternalTypeName().equals(constants.getConstantTypeName(field.getClassIndex()));
    }

    protected static byte[] getCode(Method method) {
        AttributeCode attributeCode = method.getAttribute("Code");
        return (attributeCode == null) ? null : attributeCode.getCode();
    }
}
