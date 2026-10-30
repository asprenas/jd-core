/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.api;

import org.jd.core.v1.api.loader.Loader;
import org.jd.core.v1.api.printer.Printer;

import java.util.Map;

/**
 * Decompiler of a class file into Java source.
 * <p>
 * The class file and the classes it refers to are read with a {@link Loader}; the source is written with a
 * {@link Printer}. The implementation is {@link org.jd.core.v1.ClassFileToJavaSourceDecompiler}.
 */
public interface Decompiler {
    /**
     * Decompiles a class with the default configuration.
     *
     * @param loader the loader of the class files
     * @param printer the receiver of the decompiled source
     * @param internalName the internal name of the class to decompile (e.g. 'java/lang/String')
     * @throws Exception when the class cannot be loaded or decompiled
     */
    void decompile(Loader loader, Printer printer, String internalName) throws Exception;

    /**
     * Decompiles a class with a configuration.
     * <p>
     * The boolean options are enabled with {@code Boolean.TRUE} or {@code "true"}, and are disabled when absent:
     * <ul>
     *   <li>'realignLineNumbers': realigns the source lines on the original line numbers</li>
     *   <li>'localVariableTypeInference': declares local variables with 'var' (Java 10+ class files)</li>
     *   <li>'textBlocks': prints multi-line string constants as text blocks (Java 15+ class files)</li>
     *   <li>'unnamedVariables': prints unused variables and parameters as '_' (Java 22+ class files)</li>
     *   <li>'repeatableAnnotations': prints the values of a container annotation as repeated annotations
     *   (Java 8+ class files)</li>
     * </ul>
     * When the map is mutable, the decompiler stores its type cache under the key 'typeMaker': reuse the same map
     * to decompile several classes of the same loader faster.
     *
     * @param loader the loader of the class files
     * @param printer the receiver of the decompiled source
     * @param internalName the internal name of the class to decompile (e.g. 'java/lang/String')
     * @param configuration the options
     * @throws Exception when the class cannot be loaded or decompiled
     */
    void decompile(Loader loader, Printer printer, String internalName, Map<String, Object> configuration) throws Exception;
}
