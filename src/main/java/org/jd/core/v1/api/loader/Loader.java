/*
 * Copyright (c) 2008-2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.api.loader;

/**
 * Source of the class files: the class to decompile and the classes it refers to (super types, called methods...).
 */
public interface Loader {
    /**
     * @param internalName the internal name of a class (e.g. 'java/lang/String')
     * @return true when {@link #load(String)} can return the class file
     */
    boolean canLoad(String internalName);

    /**
     * @param internalName the internal name of a class (e.g. 'java/lang/String')
     * @return the content of the class file, or null when the class is not found
     * @throws LoaderException when the class file cannot be read
     */
    byte[] load(String internalName) throws LoaderException;
}
