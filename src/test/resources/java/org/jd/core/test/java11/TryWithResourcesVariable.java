package org.jd.core.test.java11;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;

/**
 * Java 9 (JEP 213): try-with-resources on effectively final variables and final fields.
 */
public class TryWithResourcesVariable {
    private final Reader fieldReader;

    public TryWithResourcesVariable(Reader reader) {
        this.fieldReader = reader;
    }

    public void oneVariable(Reader reader) throws IOException {
        try (reader) {
            System.out.println(reader.read());
        }
    }

    public void twoVariables(InputStream in, OutputStream out) throws IOException {
        try (in; out) {
            out.write(in.read());
        }
    }

    public void mixed(InputStream in, String path) throws IOException {
        try (in; OutputStream out = new FileOutputStream(path)) {
            out.write(in.read());
        }
    }

    public void withCatchAndFinally(final Reader reader) {
        try (reader) {
            reader.read();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            System.out.println("done");
        }
    }

    public int finalField() throws IOException {
        try (fieldReader) {
            return fieldReader.read();
        }
    }

    public int thisField() throws IOException {
        try (this.fieldReader) {
            return this.fieldReader.read();
        }
    }
}
