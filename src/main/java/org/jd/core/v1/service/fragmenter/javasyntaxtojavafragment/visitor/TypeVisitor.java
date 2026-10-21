/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.visitor;

import org.jd.core.v1.api.loader.Loader;
import org.jd.core.v1.api.printer.Printer;
import org.jd.core.v1.model.classfile.ClassFile;
import org.jd.core.v1.model.classfile.Constants;
import org.jd.core.v1.model.classfile.attribute.AttributeInnerClasses;
import org.jd.core.v1.model.classfile.attribute.InnerClass;
import org.jd.core.v1.model.javafragment.ImportsFragment;
import org.jd.core.v1.model.javasyntax.AbstractJavaSyntaxVisitor;
import org.jd.core.v1.model.javasyntax.expression.Expression;
import org.jd.core.v1.model.javasyntax.reference.AnnotationReference;
import org.jd.core.v1.model.javasyntax.reference.AnnotationReferences;
import org.jd.core.v1.model.javasyntax.reference.TypeAnnotationReferences;
import org.jd.core.v1.model.javasyntax.type.*;
import org.jd.core.v1.model.token.*;
import org.jd.core.v1.service.deserializer.classfile.ClassFileDeserializer;
import org.jd.core.v1.util.DefaultList;

import java.util.HashMap;
import java.util.List;

import static org.jd.core.v1.model.javasyntax.type.PrimitiveType.*;

public class TypeVisitor extends AbstractJavaSyntaxVisitor {
    public static final KeywordToken BOOLEAN = new KeywordToken("boolean");
    public static final KeywordToken BYTE = new KeywordToken("byte");
    public static final KeywordToken CHAR = new KeywordToken("char");
    public static final KeywordToken DOUBLE = new KeywordToken("double");
    public static final KeywordToken EXPORTS = new KeywordToken("exports");
    public static final KeywordToken EXTENDS = new KeywordToken("extends");
    public static final KeywordToken FLOAT = new KeywordToken("float");
    public static final KeywordToken INT = new KeywordToken("int");
    public static final KeywordToken LONG = new KeywordToken("long");
    public static final KeywordToken MODULE = new KeywordToken("module");
    public static final KeywordToken OPEN = new KeywordToken("open");
    public static final KeywordToken OPENS = new KeywordToken("opens");
    public static final KeywordToken PROVIDES = new KeywordToken("provides");
    public static final KeywordToken REQUIRES = new KeywordToken("requires");
    public static final KeywordToken SHORT = new KeywordToken("short");
    public static final KeywordToken SUPER = new KeywordToken("super");
    public static final KeywordToken TO = new KeywordToken("to");
    public static final KeywordToken TRANSITIVE = new KeywordToken("transitive");
    public static final KeywordToken USES = new KeywordToken("uses");
    public static final KeywordToken VOID = new KeywordToken("void");
    public static final KeywordToken WITH = new KeywordToken("with");

    public static final int UNKNOWN_LINE_NUMBER = Printer.UNKNOWN_LINE_NUMBER;

    protected Loader loader;
    protected String internalPackageName;
    protected boolean genericTypesSupported;
    protected ImportsFragment importsFragment;
    protected Tokens tokens;
    protected int maxLineNumber = 0;
    protected String currentInternalTypeName;
    protected HashMap<String, TextToken> textTokenCache = new HashMap<>();
    protected HashMap<String, Boolean> nonStaticInnerTypes = new HashMap<>();

    // Type annotations of the declared type being visited: 'typeAnnotationPath' is the JVMS 'type_path' of the visited type
    protected TypeAnnotationReferences typeAnnotationReferences;
    protected int typeAnnotationTarget;
    protected int typeAnnotationIndex;
    protected String typeAnnotationPath = "";

    public TypeVisitor(Loader loader, String mainInternalTypeName, int majorVersion, ImportsFragment importsFragment) {
        this.loader = loader;
        this.genericTypesSupported = (majorVersion >= 49); // (majorVersion >= Java 5)
        this.importsFragment = importsFragment;

        int index = mainInternalTypeName.lastIndexOf('/');
        this.internalPackageName = (index == -1) ? "" : mainInternalTypeName.substring(0, index+1);
    }

    @Override
    public void visit(TypeArguments arguments) {
        if (typeAnnotationReferences == null) {
            buildTokensForList(arguments, TextToken.COMMA_SPACE);
        } else {
            String path = typeAnnotationPath;
            int size = arguments.size();

            for (int i=0; i<size; i++) {
                if (i > 0) {
                    tokens.add(TextToken.COMMA_SPACE);
                }
                typeAnnotationPath = path + "3," + i + ";";
                arguments.get(i).accept(this);
            }

            typeAnnotationPath = path;
        }
    }

    /**
     * Visit a declared type and print its type annotations.
     *
     * @param path 'type_path' of 'type' in the declared type
     */
    protected void visitAnnotatedType(BaseType type, TypeAnnotationReferences references, int target, int index, String path) {
        visitAnnotated(type, references, target, index, path);
    }

    protected void visitAnnotatedType(BaseTypeParameter typeParameters, TypeAnnotationReferences references, int target, int index, String path) {
        visitAnnotated(typeParameters, references, target, index, path);
    }

    private void visitAnnotated(Object visitable, TypeAnnotationReferences references, int target, int index, String path) {
        TypeAnnotationReferences oldReferences = typeAnnotationReferences;
        int oldTarget = typeAnnotationTarget;
        int oldIndex = typeAnnotationIndex;
        String oldPath = typeAnnotationPath;

        typeAnnotationReferences = references;
        typeAnnotationTarget = target;
        typeAnnotationIndex = index;
        typeAnnotationPath = path;

        if (visitable instanceof BaseType) {
            ((BaseType)visitable).accept(this);
        } else {
            ((BaseTypeParameter)visitable).accept(this);
        }

        typeAnnotationReferences = oldReferences;
        typeAnnotationTarget = oldTarget;
        typeAnnotationIndex = oldIndex;
        typeAnnotationPath = oldPath;
    }

    /**
     * Print the type annotations of the type at 'path', followed by a space.
     */
    protected void visitTypeAnnotations(String path) {
        if (typeAnnotationReferences != null) {
            AnnotationReferences<AnnotationReference> references = typeAnnotationReferences.get(typeAnnotationTarget, typeAnnotationIndex, path);

            if (references != null) {
                TypeAnnotationReferences oldReferences = typeAnnotationReferences;

                // The types of the annotations are not annotated
                typeAnnotationReferences = null;
                references.accept(this);
                tokens.add(TextToken.SPACE);
                typeAnnotationReferences = oldReferences;
            }
        }
    }

    /**
     * @return 'type_path' of the element type of an array: one step per dimension
     */
    protected String elementTypeAnnotationPath(int dimension) {
        StringBuilder sb = new StringBuilder(typeAnnotationPath);

        for (int i=0; i<dimension; i++) {
            sb.append("0,0;");
        }

        return sb.toString();
    }

    @Override
    public void visit(DiamondTypeArgument argument) {}

    @Override
    public void visit(WildcardExtendsTypeArgument argument) {
        visitTypeAnnotations(typeAnnotationPath);
        tokens.add(TextToken.QUESTIONMARK_SPACE);
        tokens.add(EXTENDS);
        tokens.add(TextToken.SPACE);

        visitWildcardBound(argument.getType());
    }

    protected void visitWildcardBound(BaseType type) {
        String path = typeAnnotationPath;

        typeAnnotationPath = path + "2,0;";
        type.accept(this);
        typeAnnotationPath = path;
    }

    @Override
    public void visit(PrimitiveType type) {
        visitTypeAnnotations(elementTypeAnnotationPath(type.getDimension()));

        switch (type.getJavaPrimitiveFlags()) {
            case FLAG_BOOLEAN: tokens.add(BOOLEAN); break;
            case FLAG_CHAR:    tokens.add(CHAR);    break;
            case FLAG_FLOAT:   tokens.add(FLOAT);   break;
            case FLAG_DOUBLE:  tokens.add(DOUBLE);  break;
            case FLAG_BYTE:    tokens.add(BYTE);    break;
            case FLAG_SHORT:   tokens.add(SHORT);   break;
            case FLAG_INT:     tokens.add(INT);     break;
            case FLAG_LONG:    tokens.add(LONG);    break;
            case FLAG_VOID:    tokens.add(VOID);    break;
        }

        // Build token for dimension
        visitAnnotatedDimension(type.getDimension(), typeAnnotationPath);
    }

    @Override
    public void visit(ObjectType type) {
        String path = typeAnnotationPath;
        String elementPath = elementTypeAnnotationPath(type.getDimension());

        // Build token for type reference
        ReferenceToken typeReferenceToken = newTypeReferenceToken(type, currentInternalTypeName);

        if ((typeAnnotationReferences != null) && (typeAnnotationReferences.get(typeAnnotationTarget, typeAnnotationIndex, elementPath) != null)) {
            String printedName = typeReferenceToken.getName();
            String name = type.getName();

            if (!printedName.equals(name) && printedName.endsWith("." + name)) {
                // 'java.sql.@A Date': an annotation of a qualified type is before the simple name (JLS 9.7.4)
                tokens.add(newTextToken(printedName.substring(0, printedName.length() - name.length())));
                visitTypeAnnotations(elementPath);
                typeReferenceToken = new ReferenceToken(ReferenceToken.TYPE, type.getInternalName(), name, null, currentInternalTypeName);
            } else {
                visitTypeAnnotations(elementPath);
            }
        }

        tokens.add(typeReferenceToken);

        if (genericTypesSupported) {
            // Build token for type arguments
            BaseTypeArgument typeArguments = type.getTypeArguments();

            if (typeArguments != null) {
                typeAnnotationPath = elementPath;
                visitTypeArgumentList(typeArguments);
                typeAnnotationPath = path;
            }
        }

        // Build token for dimension
        visitAnnotatedDimension(type.getDimension(), path);
    }

    protected boolean isNonStaticInnerType(String internalTypeName) {
        return nonStaticInnerTypes.computeIfAbsent(internalTypeName, this::loadNonStaticInnerType);
    }

    /** The 'InnerClasses' attribute of the class file of the inner type gives its flags */
    protected boolean loadNonStaticInnerType(String internalTypeName) {
        try {
            if ((loader != null) && loader.canLoad(internalTypeName)) {
                ClassFile classFile = new ClassFileDeserializer().loadClassFile(loader, internalTypeName);
                AttributeInnerClasses attribute = classFile.getAttribute("InnerClasses");

                if (attribute != null) {
                    for (InnerClass innerClass : attribute.getInnerClasses()) {
                        if (internalTypeName.equals(innerClass.getInnerTypeName())) {
                            return (innerClass.getInnerAccessFlags() & Constants.ACC_STATIC) == 0;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Not loaded: supposed static
        }

        return false;
    }

    @Override
    public void visit(InnerObjectType type) {
        String path = typeAnnotationPath;
        String elementPath = elementTypeAnnotationPath(type.getDimension());
        // A non-static inner type is one 'type_path' step (kind 1) deeper than its outer type: 'Outer.@A Inner' is at '1,0;'
        // and '@A Outer.Inner' at the path of the type. Without annotation at this step, the class file of the inner type
        // tells if it is static
        String innerPath = elementPath + "1,0;";
        boolean nonStatic = (typeAnnotationReferences != null) &&
            ((typeAnnotationReferences.get(typeAnnotationTarget, typeAnnotationIndex, innerPath) != null) ||
             ((typeAnnotationReferences.get(typeAnnotationTarget, typeAnnotationIndex, elementPath) != null) && isNonStaticInnerType(type.getInternalName())));

        if (nonStatic || (currentInternalTypeName == null) || (!currentInternalTypeName.equals(type.getInternalName()) && !currentInternalTypeName.equals(type.getOuterType().getInternalName()))) {
            BaseType outerType = type.getOuterType();
            TypeAnnotationReferences references = typeAnnotationReferences;

            if (nonStatic) {
                // '@B Outer.@A Inner'
                typeAnnotationPath = elementPath;
            } else {
                typeAnnotationReferences = null;
            }
            outerType.accept(this);
            typeAnnotationReferences = references;
            typeAnnotationPath = path;
            tokens.add(TextToken.DOT);
        }

        if (nonStatic) {
            elementPath = innerPath;
        }

        // 'Outer.@A Inner'
        visitTypeAnnotations(elementPath);

        // Build token for type reference
        tokens.add(new ReferenceToken(ReferenceToken.TYPE, type.getInternalName(), type.getName(), null, currentInternalTypeName));

        if (genericTypesSupported) {
            // Build token for type arguments
            BaseTypeArgument typeArguments = type.getTypeArguments();

            if (typeArguments != null) {
                typeAnnotationPath = elementPath;
                visitTypeArgumentList(typeArguments);
                typeAnnotationPath = path;
            }
        }

        // Build token for dimension
        visitAnnotatedDimension(type.getDimension(), path);
    }

    protected void visitTypeArgumentList(BaseTypeArgument arguments) {
        if (arguments != null) {
            tokens.add(TextToken.LEFTANGLEBRACKET);

            if ((typeAnnotationReferences != null) && !arguments.isTypeArgumentList()) {
                String path = typeAnnotationPath;

                typeAnnotationPath = path + "3,0;";
                arguments.accept(this);
                typeAnnotationPath = path;
            } else {
                arguments.accept(this);
            }

            tokens.add(TextToken.RIGHTANGLEBRACKET);
        }
    }

    /**
     * 'String @A [] @B []': the first '[]' is the array type ('type_path' of the array), the next ones are the
     * component types (one more '0,0;' step each)
     */
    protected void visitAnnotatedDimension(int dimension, String path) {
        if ((typeAnnotationReferences == null) || (dimension == 0)) {
            visitDimension(dimension);
            return;
        }

        String p = path;

        for (int i=0; i<dimension; i++) {
            if (typeAnnotationReferences.get(typeAnnotationTarget, typeAnnotationIndex, p) != null) {
                tokens.add(TextToken.SPACE);
                visitTypeAnnotations(p);
            }
            tokens.add(TextToken.DIMENSION_1);
            p += "0,0;";
        }
    }

    protected void visitDimension(int dimension) {
        switch (dimension) {
            case 0: break;
            case 1: tokens.add(TextToken.DIMENSION_1); break;
            case 2: tokens.add(TextToken.DIMENSION_2); break;
            default: tokens.add(newTextToken(new String(new char[dimension]).replaceAll("\0", "[]"))); break;
        }
    }

    @Override
    public void visit(WildcardSuperTypeArgument argument) {
        visitTypeAnnotations(typeAnnotationPath);
        tokens.add(TextToken.QUESTIONMARK_SPACE);
        tokens.add(SUPER);
        tokens.add(TextToken.SPACE);

        visitWildcardBound(argument.getType());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void visit(Types types) {
        buildTokensForList(types, TextToken.COMMA_SPACE);
    }

    @Override
    public void visit(TypeParameter parameter) {
        visitTypeAnnotations(typeAnnotationPath);
        tokens.add(newTextToken(parameter.getIdentifier()));
    }

    @Override
    public void visit(TypeParameterWithTypeBounds parameter) {
        visitTypeAnnotations(typeAnnotationPath);
        tokens.add(newTextToken(parameter.getIdentifier()));
        tokens.add(TextToken.SPACE);
        tokens.add(EXTENDS);
        tokens.add(TextToken.SPACE);

        BaseType types = parameter.getTypeBounds();

        if (typeAnnotationReferences == null) {
            if (types.isList()) {
                buildTokensForList(types.getList(), TextToken.SPACE_AND_SPACE);
            } else {
                BaseType type = types.getFirst();

                type.accept(this);
            }
        } else {
            // 'T extends @A Number & @B Comparable<T>': type parameter target 0x00 / 0x01, bound target 0x11 / 0x12
            int boundTarget = typeAnnotationTarget + TypeAnnotationReferences.CLASS_TYPE_PARAMETER_BOUND;
            int baseIndex = typeAnnotationIndex * 256;
            List<Type> list = types.isList() ? types.getList() : java.util.Collections.singletonList(types.getFirst());

            for (int i=0, size=list.size(); i<size; i++) {
                if (i > 0) {
                    tokens.add(TextToken.SPACE_AND_SPACE);
                }
                visitAnnotatedType(list.get(i), typeAnnotationReferences, boundTarget, baseIndex + i, "");
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void visit(TypeParameters parameters) {
        int size = parameters.size();

        if (size > 0) {
            int index = typeAnnotationIndex;

            typeAnnotationIndex = 0;
            parameters.get(0).accept(this);

            for (int i=1; i<size; i++) {
                tokens.add(TextToken.COMMA_SPACE);
                typeAnnotationIndex = i;
                parameters.get(i).accept(this);
            }

            typeAnnotationIndex = index;
        }
    }

    @Override
    public void visit(GenericType type) {
        visitTypeAnnotations(elementTypeAnnotationPath(type.getDimension()));
        tokens.add(newTextToken(type.getName()));
        visitAnnotatedDimension(type.getDimension(), typeAnnotationPath);
    }

    @Override
    public void visit(WildcardTypeArgument type) {
        visitTypeAnnotations(typeAnnotationPath);
        tokens.add(TextToken.QUESTIONMARK);
    }

    protected <T extends TypeArgumentVisitable> void buildTokensForList(List<T> list, TextToken separator) {
        int size = list.size();

        if (size > 0) {
            list.get(0).accept(this);

            for (int i=1; i<size; i++) {
                tokens.add(separator);
                list.get(i).accept(this);
            }
        }
    }

    protected ReferenceToken newTypeReferenceToken(ObjectType ot, String ownerInternalName) {
        String internalName = ot.getInternalName();
        String qualifiedName = ot.getQualifiedName();
        String name = ot.getName();

        if (packageContainsType(internalPackageName, internalName)) {
            // In the current package
            return new ReferenceToken(ReferenceToken.TYPE, internalName, name, null, ownerInternalName);
        } else {
            if (packageContainsType("java/lang/", internalName)) {
                // A 'java.lang' class
                String internalLocalTypeName = internalPackageName + name;

                if (loader.canLoad(internalLocalTypeName)) {
                    return new ReferenceToken(ReferenceToken.TYPE, internalName, qualifiedName, null, ownerInternalName);
                } else {
                    return new ReferenceToken(ReferenceToken.TYPE, internalName, name, null, ownerInternalName);
                }
            } else {
                return new TypeReferenceToken(importsFragment, internalName, qualifiedName, name, ownerInternalName);
            }
        }
    }

    protected static boolean packageContainsType(String internalPackageName, String internalClassName) {
        if (internalClassName.startsWith(internalPackageName)) {
            return internalClassName.indexOf('/', internalPackageName.length()) == -1;
        } else {
            return false;
        }
    }

    private static class TypeReferenceToken extends ReferenceToken {
        protected ImportsFragment importsFragment;
        protected String qualifiedName;

        public TypeReferenceToken(ImportsFragment importsFragment, String internalTypeName, String qualifiedName, String name, String ownerInternalName) {
            super(TYPE, internalTypeName, name, null, ownerInternalName);
            this.importsFragment = importsFragment;
            this.qualifiedName = qualifiedName;
        }

        @Override
        public String getName() {
            if (importsFragment.contains(internalTypeName)) {
                return name;
            } else {
                return qualifiedName;
            }
        }
    }

    protected TextToken newTextToken(String text) {
        TextToken textToken = textTokenCache.get(text);

        if (textToken == null) {
            textTokenCache.put(text, textToken=new TextToken(text));
        }

        return textToken;
    }

    public class Tokens extends DefaultList<Token> {
        protected int currentLineNumber = UNKNOWN_LINE_NUMBER;

        public int getCurrentLineNumber() {
            return currentLineNumber;
        }

        @Override
        public boolean add(Token token) {
            assert !(token instanceof LineNumberToken);
            return super.add(token);
        }

        public void addLineNumberToken(Expression expression) {
            addLineNumberToken(expression.getLineNumber());
        }

        public void addLineNumberToken(int lineNumber) {
            if (lineNumber != UNKNOWN_LINE_NUMBER) {
                if (lineNumber >= maxLineNumber) {
                    super.add(new LineNumberToken(lineNumber));
                    maxLineNumber = currentLineNumber = lineNumber;
                }
            }
        }
    }
}
