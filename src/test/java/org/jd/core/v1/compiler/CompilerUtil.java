/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.compiler;

import javax.tools.*;
import java.io.File;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

public class CompilerUtil {
    protected static final File DESTINATION_DIRECTORY = new File("build/test-recompiled");
    protected static final String DESTINATION_DIRECTORY_PATH = DESTINATION_DIRECTORY.getAbsolutePath();

    public static boolean compile(String preferredJavaVersion, JavaFileObject... javaFileObjects) throws Exception {
        boolean compilationSuccess = false;

        DESTINATION_DIRECTORY.mkdirs();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StringWriter writer = new StringWriter();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        // Compile against the matching platform API, not the running JDK's (e.g. JDK 21 SequencedCollection).
        // The versions older than the oldest release of the running javac ('1.5' -> 8 on JDK 25) are raised.
        // -Xlint:-options hides 'source value 8 is obsolete and will be removed in a future release' (JDK 21+)
        int release = Math.max(parseJavaVersion(preferredJavaVersion), oldestRelease(compiler));
        List<String> options = Arrays.asList("--release", String.valueOf(release), "-Xlint:-options", "-d", DESTINATION_DIRECTORY_PATH, "-cp", System.getProperty("java.class.path"));
        List<JavaFileObject> compilationUnits = Arrays.asList(javaFileObjects);

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            compilationSuccess = compiler.getTask(writer, fileManager, diagnostics, options, null, compilationUnits).call();

            if (!diagnostics.getDiagnostics().isEmpty()) {
                StringBuilder sb = new StringBuilder();

                for (Diagnostic d : diagnostics.getDiagnostics()) {
                    switch (d.getKind()) {
                        case NOTE:
                        case WARNING:
                            break;
                        default:
                            if (d.getLineNumber() > 0) {
                                sb.append(String.format("%-7s - line %-4d- %s%n", d.getKind(), d.getLineNumber(), d.getMessage(null)));
                            } else {
                                sb.append(String.format("%-7s -          - %s%n", d.getKind(), d.getMessage(null)));
                            }
                            break;
                    }
                }

                if (sb.length() > 0) {
                    System.err.println(compilationUnits.get(0).getName());
                    System.err.print(sb.toString());
                }
            }
        }

        return compilationSuccess;
    }

    private static int oldestRelease = -1;

    /** The oldest '--release' value supported by the running javac: an unsupported one is rejected by 'getTask' */
    private static synchronized int oldestRelease(JavaCompiler compiler) {
        if (oldestRelease == -1) {
            oldestRelease = Runtime.version().feature();

            for (int release = 6; release < Runtime.version().feature(); release++) {
                try {
                    compiler.getTask(null, null, null, Arrays.asList("--release", String.valueOf(release)), null, null);
                    oldestRelease = release;
                    break;
                } catch (IllegalArgumentException e) {
                    // Not supported
                }
            }
        }

        return oldestRelease;
    }

    private static int parseJavaVersion(String javaVersion) {
        if(javaVersion.startsWith("1.")) {
            javaVersion = javaVersion.substring(2, 3);
        } else {
            int index = javaVersion.indexOf(".");

            if(index != -1) {
                javaVersion = javaVersion.substring(0, index);
            }
        }

        return Integer.parseInt(javaVersion);
    }
}
