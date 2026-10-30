/*
 * Copyright (c) 2008-2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.api.printer;

/**
 * Receiver of the decompiled source.
 * <p>
 * The source is sent as a sequence of calls: {@link #start}, then lines ({@link #startLine} ... {@link #endLine})
 * containing text, keywords, constants, declarations and references, then {@link #end}. An implementation can
 * build plain text, or keep the declarations and references to make links (e.g. in an IDE).
 */
public interface Printer {
    /**
     * Called before the source.
     *
     * @param maxLineNumber the highest original line number, or {@link #UNKNOWN_LINE_NUMBER}
     * @param majorVersion the major version of the class file (e.g. 52 for Java 8)
     * @param minorVersion the minor version of the class file
     */
    void start(int maxLineNumber, int majorVersion, int minorVersion);
    /** Called after the source. */
    void end();

    /** @param text text without line break: punctuation, operators, names of local variables... */
    void printText(String text);
    /** @param constant a numeric literal, as written in the source (e.g. '1L', '0x1F') */
    void printNumericConstant(String constant);
    /**
     * @param constant a string or char literal, with quotes and escapes
     * @param ownerInternalName the internal name of the class containing the literal
     */
    void printStringConstant(String constant, String ownerInternalName);
    /** @param keyword a Java keyword (e.g. 'public', 'class', 'return') */
    void printKeyword(String keyword);

    // Declaration & reference types
    /** Declaration or reference type: class, interface, enum, record or annotation */
    int TYPE = 1;
    /** Declaration or reference type: field */
    int FIELD = 2;
    /** Declaration or reference type: method */
    int METHOD = 3;
    /** Declaration or reference type: constructor */
    int CONSTRUCTOR = 4;
    /** Declaration or reference type: package */
    int PACKAGE = 5;
    /** Declaration or reference type: module */
    int MODULE = 6;

    /**
     * Prints the name of a declared type, field, method, constructor, package or module.
     *
     * @param type {@link #TYPE}, {@link #FIELD}, {@link #METHOD}, {@link #CONSTRUCTOR}, {@link #PACKAGE} or
     *             {@link #MODULE}
     * @param internalTypeName the internal name of the type, or of the declaring type of a member
     * @param name the name to print
     * @param descriptor the descriptor of a field or method, null for the other types
     */
    void printDeclaration(int type, String internalTypeName, String name, String descriptor);
    /**
     * Prints the name of a referenced type, field, method, constructor, package or module.
     *
     * @param type see {@link #printDeclaration}
     * @param internalTypeName the internal name of the type, or of the declaring type of a member
     * @param name the name to print
     * @param descriptor the descriptor of a field or method, null for the other types
     * @param ownerInternalName the internal name of the class containing the reference
     */
    void printReference(int type, String internalTypeName, String name, String descriptor, String ownerInternalName);

    /** Increases the indentation of the next lines. */
    void indent();
    /** Decreases the indentation of the next lines. */
    void unindent();

    /** Line number of a line without original line number */
    int UNKNOWN_LINE_NUMBER = 0;

    /** @param lineNumber the original line number of the line, or {@link #UNKNOWN_LINE_NUMBER} */
    void startLine(int lineNumber);
    /** Ends the current line. */
    void endLine();
    /** @param count the number of empty lines to print, without original line number */
    void extraLine(int count);

    // Marker types
    /** Marker type: comment */
    int COMMENT = 1;
    /** Marker type: Javadoc comment */
    int JAVADOC = 2;
    /** Marker type: error message of the decompiler */
    int ERROR = 3;
    /** Marker type: import statements, e.g. to fold them */
    int IMPORT_STATEMENTS = 4;

    /** @param type {@link #COMMENT}, {@link #JAVADOC}, {@link #ERROR} or {@link #IMPORT_STATEMENTS} */
    void startMarker(int type);
    /** @param type the type given to the matching {@link #startMarker} */
    void endMarker(int type);
}
