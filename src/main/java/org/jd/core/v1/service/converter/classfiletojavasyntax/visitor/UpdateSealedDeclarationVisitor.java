/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.visitor;

import org.jd.core.v1.api.loader.Loader;
import org.jd.core.v1.model.classfile.ClassFile;
import org.jd.core.v1.model.classfile.attribute.AttributePermittedSubclasses;
import org.jd.core.v1.model.javasyntax.declaration.InterfaceDeclaration;
import org.jd.core.v1.model.javasyntax.type.ObjectType;
import org.jd.core.v1.model.javasyntax.type.Types;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.ClassFileBodyDeclaration;
import org.jd.core.v1.service.converter.classfiletojavasyntax.model.javasyntax.declaration.ClassFileTypeDeclaration;
import org.jd.core.v1.service.converter.classfiletojavasyntax.util.TypeMaker;
import org.jd.core.v1.service.deserializer.classfile.ClassFileDeserializer;

import java.util.HashMap;

import static org.jd.core.v1.model.classfile.Constants.ACC_ENUM;
import static org.jd.core.v1.model.classfile.Constants.ACC_FINAL;

/**
 * Java 17+ sealed classes and interfaces (PermittedSubclasses attribute): 'sealed ... permits ...'.
 * A subclass of a sealed type that is neither final nor sealed is 'non-sealed'. The super types are only
 * searched in the same compilation unit (outer and inner types).
 */
public class UpdateSealedDeclarationVisitor {
    protected TypeMaker typeMaker;
    // Sealed types of the other class files, by internal name
    protected HashMap<String, Boolean> sealedTypes = new HashMap<>();

    public UpdateSealedDeclarationVisitor(TypeMaker typeMaker) {
        this.typeMaker = typeMaker;
    }

    public void visit(InterfaceDeclaration declaration) {
        if (declaration.getBodyDeclaration() == null) {
            return;
        }

        ClassFileBodyDeclaration bodyDeclaration = (ClassFileBodyDeclaration)declaration.getBodyDeclaration();
        ClassFile classFile = bodyDeclaration.getClassFile();
        AttributePermittedSubclasses permitted = classFile.getAttribute("PermittedSubclasses");

        if ((permitted != null) && (permitted.getInternalTypeNames() != null)) {
            String[] names = permitted.getInternalTypeNames();

            if (names.length == 1) {
                declaration.setPermittedSubtypes(typeMaker.makeFromInternalTypeName(names[0]));
            } else {
                Types types = new Types(names.length);
                for (String name : names) {
                    types.add(typeMaker.makeFromInternalTypeName(name));
                }
                declaration.setPermittedSubtypes(types);
            }
        } else if (((classFile.getAccessFlags() & (ACC_FINAL|ACC_ENUM)) == 0) && (classFile.getAttribute("Record") == null) && hasSealedSuperType(bodyDeclaration, classFile)) {
            declaration.setNonSealed(true);
        }
    }

    protected boolean hasSealedSuperType(ClassFileBodyDeclaration bodyDeclaration, ClassFile classFile) {
        if (isSealed(bodyDeclaration, classFile.getSuperTypeName())) {
            return true;
        }

        if (classFile.getInterfaceTypeNames() != null) {
            for (String name : classFile.getInterfaceTypeNames()) {
                if (isSealed(bodyDeclaration, name)) {
                    return true;
                }
            }
        }

        return false;
    }

    protected boolean isSealed(ClassFileBodyDeclaration bodyDeclaration, String internalTypeName) {
        if (internalTypeName == null) {
            return false;
        }

        ClassFile classFile = findClassFile(bodyDeclaration, internalTypeName);

        if (classFile != null) {
            return classFile.getAttribute("PermittedSubclasses") != null;
        }

        // A super type of another class file: 'final class Circle implements Shape' and 'sealed interface Shape' in
        // two files. Without it, a subclass that is not 'final' is not written 'non-sealed' and does not compile
        return sealedTypes.computeIfAbsent(internalTypeName, this::loadSealed);
    }

    protected boolean loadSealed(String internalTypeName) {
        Loader loader = typeMaker.getLoader();

        try {
            if ((loader != null) && loader.canLoad(internalTypeName)) {
                ClassFile classFile = new ClassFileDeserializer().loadClassFile(loader, internalTypeName);
                return classFile.getAttribute("PermittedSubclasses") != null;
            }
        } catch (Exception e) {
            // Not loaded: not known as sealed
        }

        return false;
    }

    protected static ClassFile findClassFile(ClassFileBodyDeclaration bodyDeclaration, String internalTypeName) {
        for (ClassFileBodyDeclaration bd = bodyDeclaration; bd != null; bd = bd.getOuterBodyDeclaration()) {
            if (internalTypeName.equals(bd.getClassFile().getInternalTypeName())) {
                return bd.getClassFile();
            }
        }

        ClassFileTypeDeclaration typeDeclaration = bodyDeclaration.getInnerTypeDeclaration(internalTypeName);

        if ((typeDeclaration != null) && (typeDeclaration.getBodyDeclaration() != null)) {
            return ((ClassFileBodyDeclaration)typeDeclaration.getBodyDeclaration()).getClassFile();
        }

        return null;
    }
}
