/*
 * Copyright (c) 2008-2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.api.loader;

/** Error while reading a class file in a {@link Loader}. */
public class LoaderException extends Exception {
    private static final long serialVersionUID = 9506606333927794L;
    /** Creates an exception without message. */
    public LoaderException() {}

    /** @param msg the message */
    public LoaderException(String msg) { super(msg); }

    /** @param cause the cause, e.g. an {@link java.io.IOException} */
    public LoaderException(Throwable cause) { super(cause); }
}
