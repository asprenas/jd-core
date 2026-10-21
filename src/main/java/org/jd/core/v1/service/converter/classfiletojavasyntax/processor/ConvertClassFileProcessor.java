/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.processor;

import org.jd.core.v1.model.classfile.ClassFile;
import org.jd.core.v1.model.classfile.Constants;
import org.jd.core.v1.model.classfile.Field;
import org.jd.core.v1.model.classfile.Method;
import org.jd.core.v1.model.classfile.attribute.*;
import org.jd.core.v1.model.classfile.constant.*;
import org.jd.core.v1.model.javasyntax.CompilationUnit;
import org.jd.core.v1.model.javasyntax.declaration.*;
import org.jd.core.v1.model.javasyntax.expression.*;
import org.jd.core.v1.model.javasyntax.reference.BaseAnnotationReference;
import org.jd.core.v1.model.javasyntax.reference.ElementValue;
import org.jd.core.v1.model.javasyntax.reference.TypeAnnotationReferences;
import org.jd.core.v1.model.javasyntax.type.*;
import org.jd.core.v1.model.message.Message;
import org.jd.core.v1.model.processor.Processor;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.*;
import org.jd.core.v1.service.converter.classfiletojavasyntax.util.AnnotationConverter;
import org.jd.core.v1.service.converter.classfiletojavasyntax.util.TypeMaker;
import org.jd.core.v1.service.converter.classfiletojavasyntax.visitor.PopulateBindingsWithTypeParameterVisitor;
import org.jd.core.v1.util.DefaultList;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.jd.core.v1.model.classfile.Constants.*;

/**
 * Convert ClassFile model to Java syntax model.<br><br>
 *
 * Input:  {@link org.jd.core.v1.model.classfile.ClassFile}<br>
 * Output: {@link org.jd.core.v1.model.javasyntax.CompilationUnit}<br>
 */
public class ConvertClassFileProcessor implements Processor {
    protected PopulateBindingsWithTypeParameterVisitor populateBindingsWithTypeParameterVisitor = new PopulateBindingsWithTypeParameterVisitor() {
        @Override
        public void visit(TypeParameter parameter) {
            bindings.put(parameter.getIdentifier(), new GenericType(parameter.getIdentifier()));
        }
        @Override
        public void visit(TypeParameterWithTypeBounds parameter) {
            bindings.put(parameter.getIdentifier(), new GenericType(parameter.getIdentifier()));
            typeBounds.put(parameter.getIdentifier(), parameter.getTypeBounds());
        }
    };

    @Override
    public void process(Message message) throws Exception {
        TypeMaker typeMaker = message.getHeader("typeMaker");
        ClassFile classFile = message.getBody();

        AnnotationConverter annotationConverter = new AnnotationConverter(typeMaker);
        Map<String, Object> configuration = message.getHeader("configuration");
        Object repeatableAnnotations = (configuration == null) ? null : configuration.get("repeatableAnnotations");

        if ((repeatableAnnotations != null) && "true".equals(repeatableAnnotations.toString()) && (classFile.getMajorVersion() >= 52)) {
            // Java 8+
            annotationConverter.setRepeatableAnnotationLoader(message.getHeader("loader"));
        }

        TypeDeclaration typeDeclaration;

        if (classFile.isEnum()) {
            typeDeclaration = convertEnumDeclaration(typeMaker, annotationConverter, classFile, null);
        } else if (classFile.isAnnotation()) {
            typeDeclaration = convertAnnotationDeclaration(typeMaker, annotationConverter, classFile, null);
        } else if (classFile.isModule()) {
            typeDeclaration = convertModuleDeclaration(classFile);
        } else if (classFile.isInterface()) {
            typeDeclaration = convertInterfaceDeclaration(typeMaker, annotationConverter, classFile, null);
        } else {
            typeDeclaration = convertClassDeclaration(typeMaker, annotationConverter, classFile, null);
        }

        message.setHeader("majorVersion", classFile.getMajorVersion());
        message.setHeader("minorVersion", classFile.getMinorVersion());
        message.setBody(new CompilationUnit(typeDeclaration));
    }

    protected ClassFileInterfaceDeclaration convertInterfaceDeclaration(TypeMaker parser, AnnotationConverter converter, ClassFile classFile, ClassFileBodyDeclaration outerClassFileBodyDeclaration) {
        BaseAnnotationReference annotationReferences = convertAnnotationReferences(converter, classFile);
        TypeMaker.TypeTypes typeTypes = parser.parseClassFileSignature(classFile);
        ClassFileBodyDeclaration bodyDeclaration = convertBodyDeclaration(parser, converter, classFile, typeTypes.typeParameters, outerClassFileBodyDeclaration);

        ClassFileInterfaceDeclaration declaration = new ClassFileInterfaceDeclaration(
                annotationReferences, classFile.getAccessFlags(),
                typeTypes.thisType.getInternalName(), typeTypes.thisType.getName(),
                typeTypes.typeParameters, typeTypes.interfaces, bodyDeclaration);
        declaration.setTypeAnnotationReferences(convertTypeAnnotationReferences(converter, classFile));
        return declaration;
    }

    protected ClassFileEnumDeclaration convertEnumDeclaration(TypeMaker parser, AnnotationConverter converter, ClassFile classFile, ClassFileBodyDeclaration outerClassFileBodyDeclaration) {
        BaseAnnotationReference annotationReferences = convertAnnotationReferences(converter, classFile);
        TypeMaker.TypeTypes typeTypes = parser.parseClassFileSignature(classFile);
        ClassFileBodyDeclaration bodyDeclaration = convertBodyDeclaration(parser, converter, classFile, typeTypes.typeParameters, outerClassFileBodyDeclaration);

        ClassFileEnumDeclaration declaration = new ClassFileEnumDeclaration(
                annotationReferences, classFile.getAccessFlags(),
                typeTypes.thisType.getInternalName(), typeTypes.thisType.getName(),
                typeTypes.interfaces, bodyDeclaration);
        declaration.setTypeAnnotationReferences(convertTypeAnnotationReferences(converter, classFile));
        return declaration;
    }

    protected ClassFileAnnotationDeclaration convertAnnotationDeclaration(TypeMaker parser, AnnotationConverter converter, ClassFile classFile, ClassFileBodyDeclaration outerClassFileBodyDeclaration) {
        BaseAnnotationReference annotationReferences = convertAnnotationReferences(converter, classFile);
        TypeMaker.TypeTypes typeTypes = parser.parseClassFileSignature(classFile);
        ClassFileBodyDeclaration bodyDeclaration = convertBodyDeclaration(parser, converter, classFile, typeTypes.typeParameters, outerClassFileBodyDeclaration);

        ClassFileAnnotationDeclaration declaration = new ClassFileAnnotationDeclaration(
                annotationReferences, classFile.getAccessFlags(),
                typeTypes.thisType.getInternalName(), typeTypes.thisType.getName(),
                bodyDeclaration);
        declaration.setTypeAnnotationReferences(convertTypeAnnotationReferences(converter, classFile));
        return declaration;
    }

    protected ClassFileClassDeclaration convertClassDeclaration(TypeMaker parser, AnnotationConverter converter, ClassFile classFile, ClassFileBodyDeclaration outerClassFileBodyDeclaration) {
        BaseAnnotationReference annotationReferences = convertAnnotationReferences(converter, classFile);
        TypeMaker.TypeTypes typeTypes = parser.parseClassFileSignature(classFile);
        ClassFileBodyDeclaration bodyDeclaration = convertBodyDeclaration(parser, converter, classFile, typeTypes.typeParameters, outerClassFileBodyDeclaration);

        ClassFileClassDeclaration declaration = new ClassFileClassDeclaration(
                annotationReferences, classFile.getAccessFlags(),
                typeTypes.thisType.getInternalName(), typeTypes.thisType.getName(),
                typeTypes.typeParameters, typeTypes.superType,
                typeTypes.interfaces, bodyDeclaration);
        declaration.setTypeAnnotationReferences(convertTypeAnnotationReferences(converter, classFile));
        return declaration;
    }

    protected ClassFileBodyDeclaration convertBodyDeclaration(TypeMaker parser, AnnotationConverter converter, ClassFile classFile, BaseTypeParameter typeParameters, ClassFileBodyDeclaration outerClassFileBodyDeclaration) {
        Map<String, TypeArgument> bindings;
        Map<String, BaseType> typeBounds;

        if (!classFile.isStatic() && (outerClassFileBodyDeclaration != null)) {
            bindings = outerClassFileBodyDeclaration.getBindings();
            typeBounds = outerClassFileBodyDeclaration.getTypeBounds();
        } else {
            bindings = Collections.emptyMap();
            typeBounds = Collections.emptyMap();
        }

        if (typeParameters != null) {
            populateBindingsWithTypeParameterVisitor.init(bindings=new HashMap<>(bindings), typeBounds=new HashMap<>(typeBounds));
            typeParameters.accept(populateBindingsWithTypeParameterVisitor);
        }

        ClassFileBodyDeclaration bodyDeclaration = new ClassFileBodyDeclaration(classFile, bindings, typeBounds, outerClassFileBodyDeclaration);

        bodyDeclaration.setAnnotationConverter(converter);

        bodyDeclaration.setFieldDeclarations(convertFields(parser, converter, classFile));
        bodyDeclaration.setMethodDeclarations(convertMethods(parser, converter, bodyDeclaration, classFile));
        bodyDeclaration.setInnerTypeDeclarations(convertInnerTypes(parser, converter, classFile, bodyDeclaration));

        return bodyDeclaration;
    }

    protected List<ClassFileFieldDeclaration> convertFields(TypeMaker parser, AnnotationConverter converter, ClassFile classFile) {
        Field[] fields = classFile.getFields();

        if (fields == null) {
            return null;
        } else {
            DefaultList<ClassFileFieldDeclaration> list = new DefaultList<>(fields.length);

            for (Field field : fields) {
                BaseAnnotationReference annotationReferences = convertAnnotationReferences(converter, field);
                Type typeField = parser.parseFieldSignature(classFile, field);
                ExpressionVariableInitializer variableInitializer = convertFieldInitializer(field, typeField);
                FieldDeclarator fieldDeclarator = new FieldDeclarator(field.getName(), variableInitializer);

                ClassFileFieldDeclaration fieldDeclaration = new ClassFileFieldDeclaration(annotationReferences, field.getAccessFlags(), typeField, fieldDeclarator);

                fieldDeclaration.setTypeAnnotationReferences(convertTypeAnnotationReferences(converter, field));
                list.add(fieldDeclaration);
            }

            return list;
        }
    }

    protected List<ClassFileConstructorOrMethodDeclaration> convertMethods(TypeMaker parser, AnnotationConverter converter, ClassFileBodyDeclaration bodyDeclaration, ClassFile classFile) {
        Method[] methods = classFile.getMethods();

        if (methods == null) {
            return null;
        } else {
            DefaultList<ClassFileConstructorOrMethodDeclaration> list = new DefaultList<>(methods.length);

            for (Method method : methods) {
                String name = method.getName();
                BaseAnnotationReference annotationReferences = convertAnnotationReferences(converter, method);
                AttributeAnnotationDefault annotationDefault = method.getAttribute("AnnotationDefault");
                ElementValue defaultAnnotationValue = null;

                if (annotationDefault != null) {
                    defaultAnnotationValue = converter.convert(annotationDefault.getDefaultValue());
                }

                TypeMaker.MethodTypes methodTypes = parser.parseMethodSignature(classFile, method);
                Map<String, TypeArgument> bindings;
                Map<String, BaseType> typeBounds;

                if ((method.getAccessFlags() & ACC_STATIC) == 0) {
                    bindings = bodyDeclaration.getBindings();
                    typeBounds = bodyDeclaration.getTypeBounds();
                } else {
                    bindings = Collections.emptyMap();
                    typeBounds = Collections.emptyMap();
                }

                if (methodTypes.typeParameters != null) {
                    populateBindingsWithTypeParameterVisitor.init(bindings=new HashMap<>(bindings), typeBounds=new HashMap<>(typeBounds));
                    methodTypes.typeParameters.accept(populateBindingsWithTypeParameterVisitor);
               }

                AttributeCode code = method.getAttribute("Code");
                int firstLineNumber = 0;

                if (code != null) {
                    AttributeLineNumberTable lineNumberTable = code.getAttribute("LineNumberTable");
                    if (lineNumberTable != null) {
                        firstLineNumber = lineNumberTable.getLineNumberTable()[0].getLineNumber();
                    }
                }

                if ("<init>".equals(name)) {
                    ClassFileConstructorDeclaration constructorDeclaration = new ClassFileConstructorDeclaration(
                            bodyDeclaration, classFile, method, annotationReferences, methodTypes.typeParameters,
                            methodTypes.parameterTypes, methodTypes.exceptionTypes, bindings, typeBounds, firstLineNumber);

                    constructorDeclaration.setTypeAnnotationReferences(convertTypeAnnotationReferences(converter, method));
                    list.add(constructorDeclaration);
                } else if ("<clinit>".equals(name)) {
                    list.add(new ClassFileStaticInitializerDeclaration(bodyDeclaration, classFile, method, bindings, typeBounds, firstLineNumber));
                } else {
                    ClassFileMethodDeclaration methodDeclaration = new ClassFileMethodDeclaration(
                            bodyDeclaration, classFile, method, annotationReferences, name, methodTypes.typeParameters,
                            methodTypes.returnedType, methodTypes.parameterTypes, methodTypes.exceptionTypes, defaultAnnotationValue,
                            bindings, typeBounds, firstLineNumber);
                    methodDeclaration.setTypeAnnotationReferences(convertTypeAnnotationReferences(converter, method));
                    if (classFile.isInterface()) {
                        if (methodDeclaration.getFlags() == Constants.ACC_PUBLIC) {
                            // For interfaces, add 'default' access flag on public methods
                            methodDeclaration.setFlags(Declaration.FLAG_PUBLIC|Declaration.FLAG_DEFAULT);
                        }
                    }
                    list.add(methodDeclaration);
                }
            }

            return list;
        }
    }

    protected List<ClassFileTypeDeclaration> convertInnerTypes(TypeMaker parser, AnnotationConverter converter, ClassFile classFile, ClassFileBodyDeclaration outerClassFileBodyDeclaration) {
        List<ClassFile> innerClassFiles = classFile.getInnerClassFiles();

        if (innerClassFiles == null) {
            return null;
        } else {
            DefaultList<ClassFileTypeDeclaration> list = new DefaultList<>(innerClassFiles.size());

            for (ClassFile innerClassFile : innerClassFiles) {
                ClassFileTypeDeclaration innerTypeDeclaration;

                if (innerClassFile.isEnum()) {
                    innerTypeDeclaration = convertEnumDeclaration(parser, converter, innerClassFile, outerClassFileBodyDeclaration);
                } else if (innerClassFile.isAnnotation()) {
                    innerTypeDeclaration = convertAnnotationDeclaration(parser, converter, innerClassFile, outerClassFileBodyDeclaration);
                } else if (innerClassFile.isInterface()) {
                    innerTypeDeclaration = convertInterfaceDeclaration(parser, converter, innerClassFile, outerClassFileBodyDeclaration);
                } else {
                    innerTypeDeclaration = convertClassDeclaration(parser, converter, innerClassFile, outerClassFileBodyDeclaration);
                }

                list.add(innerTypeDeclaration);
            }

            return list;
        }
    }

    protected BaseAnnotationReference convertAnnotationReferences(AnnotationConverter converter, ClassFile classFile) {
        Annotations visibles = classFile.getAttribute("RuntimeVisibleAnnotations");
        Annotations invisibles = classFile.getAttribute("RuntimeInvisibleAnnotations");

        return converter.convert(visibles, invisibles);
    }

    protected BaseAnnotationReference convertAnnotationReferences(AnnotationConverter converter, Field field) {
        Annotations visibles = field.getAttribute("RuntimeVisibleAnnotations");
        Annotations invisibles = field.getAttribute("RuntimeInvisibleAnnotations");

        return converter.convert(visibles, invisibles);
    }

    protected BaseAnnotationReference convertAnnotationReferences(AnnotationConverter converter, Method method) {
        Annotations visibles = method.getAttribute("RuntimeVisibleAnnotations");
        Annotations invisibles = method.getAttribute("RuntimeInvisibleAnnotations");

        return converter.convert(visibles, invisibles);
    }

    protected TypeAnnotationReferences convertTypeAnnotationReferences(AnnotationConverter converter, ClassFile classFile) {
        return converter.convert(
                classFile.getAttribute("RuntimeVisibleTypeAnnotations"), classFile.getAttribute("RuntimeInvisibleTypeAnnotations"),
                null, null, AnnotationConverter.getTypeParameterClassBounds(getSignature(classFile.getAttribute("Signature"))));
    }

    protected TypeAnnotationReferences convertTypeAnnotationReferences(AnnotationConverter converter, Field field) {
        return converter.convert(
                field.getAttribute("RuntimeVisibleTypeAnnotations"), field.getAttribute("RuntimeInvisibleTypeAnnotations"),
                new Annotations[] { field.getAttribute("RuntimeVisibleAnnotations"), field.getAttribute("RuntimeInvisibleAnnotations") },
                null);
    }

    protected TypeAnnotationReferences convertTypeAnnotationReferences(AnnotationConverter converter, Method method) {
        return converter.convert(
                method.getAttribute("RuntimeVisibleTypeAnnotations"), method.getAttribute("RuntimeInvisibleTypeAnnotations"),
                new Annotations[] { method.getAttribute("RuntimeVisibleAnnotations"), method.getAttribute("RuntimeInvisibleAnnotations") },
                new AttributeParameterAnnotations[] { method.getAttribute("RuntimeVisibleParameterAnnotations"), method.getAttribute("RuntimeInvisibleParameterAnnotations") },
                AnnotationConverter.getTypeParameterClassBounds(getSignature(method.getAttribute("Signature"))));
    }

    protected static String getSignature(AttributeSignature attribute) {
        return (attribute == null) ? null : attribute.getSignature();
    }

    protected ExpressionVariableInitializer convertFieldInitializer(Field field, Type typeField) {
        AttributeConstantValue acv = field.getAttribute("ConstantValue");

        if (acv == null) {
            return null;
        } else {
            ConstantValue constantValue = acv.getConstantValue();
            Expression expression;

            switch (constantValue.getTag()) {
                case Constant.CONSTANT_Integer:
                    expression = new IntegerConstantExpression(typeField, ((ConstantInteger)constantValue).getValue());
                    break;
                case Constant.CONSTANT_Float:
                    expression = new FloatConstantExpression(((ConstantFloat)constantValue).getValue());
                    break;
                case Constant.CONSTANT_Long:
                    expression = new LongConstantExpression(((ConstantLong)constantValue).getValue());
                    break;
                case Constant.CONSTANT_Double:
                    expression = new DoubleConstantExpression(((ConstantDouble)constantValue).getValue());
                    break;
                case Constant.CONSTANT_Utf8:
                    expression = new StringConstantExpression(((ConstantUtf8)constantValue).getValue());
                    break;
                default:
                    throw new ConvertClassFileException("Invalid attributes");
            }

            return new ExpressionVariableInitializer(expression);
        }
    }

    protected ModuleDeclaration convertModuleDeclaration(ClassFile classFile) {
        AttributeModule attributeModule = classFile.getAttribute("Module");
        List<ModuleDeclaration.ModuleInfo> requires = convertModuleDeclarationModuleInfo(attributeModule.getRequires());
        List<ModuleDeclaration.PackageInfo> exports = convertModuleDeclarationPackageInfo(attributeModule.getExports());
        List<ModuleDeclaration.PackageInfo> opens = convertModuleDeclarationPackageInfo(attributeModule.getOpens());
        DefaultList<String> uses = new DefaultList<>(attributeModule.getUses());
        List<ModuleDeclaration.ServiceInfo> provides = convertModuleDeclarationServiceInfo(attributeModule.getProvides());

        return new ModuleDeclaration(
                attributeModule.getFlags(), classFile.getInternalTypeName(), attributeModule.getName(),
                attributeModule.getVersion(), requires, exports, opens, uses, provides);
    }

    protected List<ModuleDeclaration.ModuleInfo> convertModuleDeclarationModuleInfo(ModuleInfo[] moduleInfos) {
        if ((moduleInfos == null) || (moduleInfos.length == 0)) {
            return null;
        } else {
            DefaultList<ModuleDeclaration.ModuleInfo> list = new DefaultList<>(moduleInfos.length);

            for (ModuleInfo moduleInfo : moduleInfos) {
                list.add(new ModuleDeclaration.ModuleInfo(moduleInfo.getName(), moduleInfo.getFlags(), moduleInfo.getVersion()));
            }

            return list;
        }
    }

    protected List<ModuleDeclaration.PackageInfo> convertModuleDeclarationPackageInfo(PackageInfo[] packageInfos) {
        if ((packageInfos == null) || (packageInfos.length == 0)) {
            return null;
        } else {
            DefaultList<ModuleDeclaration.PackageInfo> list = new DefaultList<>(packageInfos.length);

            for (PackageInfo packageInfo : packageInfos) {
                DefaultList<String> moduleInfoNames = (packageInfo.getModuleInfoNames() == null) ?
                        null : new DefaultList<String>(packageInfo.getModuleInfoNames());
                list.add(new ModuleDeclaration.PackageInfo(packageInfo.getInternalName(), packageInfo.getFlags(), moduleInfoNames));
            }

            return list;
        }
    }

    protected List<ModuleDeclaration.ServiceInfo> convertModuleDeclarationServiceInfo(ServiceInfo[] serviceInfos) {
        if ((serviceInfos == null) || (serviceInfos.length == 0)) {
            return null;
        } else {
            DefaultList<ModuleDeclaration.ServiceInfo> list = new DefaultList<>(serviceInfos.length);

            for (ServiceInfo serviceInfo : serviceInfos) {
                DefaultList<String> implementationTypeNames = (serviceInfo.getImplementationTypeNames() == null) ?
                        null : new DefaultList<String>(serviceInfo.getImplementationTypeNames());
                list.add(new ModuleDeclaration.ServiceInfo(serviceInfo.getInterfaceTypeName(), implementationTypeNames));
            }

            return list;
        }
    }
}
