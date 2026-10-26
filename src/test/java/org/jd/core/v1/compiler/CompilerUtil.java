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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CompilerUtil {
    protected static final File DESTINATION_DIRECTORY = new File("build/test-recompiled");
    protected static final String DESTINATION_DIRECTORY_PATH = DESTINATION_DIRECTORY.getAbsolutePath();

    public static boolean compile(String preferredJavaVersion, JavaFileObject... javaFileObjects) throws Exception {
        DESTINATION_DIRECTORY.mkdirs();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        // Compile against the matching platform API, not the running JDK's (e.g. JDK 21 SequencedCollection).
        // The versions older than the oldest release of the running javac ('1.5' -> 8 on JDK 25) are raised.
        // -Xlint:-options hides 'source value 8 is obsolete and will be removed in a future release' (JDK 21+)
        int release = Math.max(parseJavaVersion(preferredJavaVersion), oldestRelease(compiler));

        for (JavaFileObject javaFileObject : javaFileObjects) {
            if (javaFileObject.getName().endsWith("module-info.java")) {
                // A module declaration needs '--release 9' or more
                release = Math.max(release, 9);
            }
        }

        List<String> options = Arrays.asList("--release", String.valueOf(release), "-Xlint:-options", "-d", DESTINATION_DIRECTORY_PATH, "-cp", System.getProperty("java.class.path"));

        return compile(compiler, options, Arrays.asList(javaFileObjects));
    }

    protected static final Pattern MODULE_NAME = Pattern.compile("module\\s+([\\w.]+)\\s*\\{");

    /**
     * Compile a decompiled 'module-info.java' alone: the packages of the module come from its original classes
     * ('--patch-module'), the required modules from the jars of the class path ('--module-path').
     *
     * @param moduleContent the jar or the directory of the original classes of the module
     */
    public static boolean compileModuleInfo(String preferredJavaVersion, String source, File moduleContent) throws Exception {
        Matcher matcher = MODULE_NAME.matcher(source);

        if (!matcher.find()) {
            return false;
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int release = Math.max(9, Math.max(parseJavaVersion(preferredJavaVersion), oldestRelease(compiler)));
        StringBuilder modulePath = new StringBuilder();

        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            // Only the jars: a directory without 'module-info.class' is not a module
            if (entry.endsWith(".jar") && !new File(entry).getAbsoluteFile().equals(moduleContent.getAbsoluteFile())) {
                if (modulePath.length() > 0) {
                    modulePath.append(File.pathSeparatorChar);
                }
                modulePath.append(entry);
            }
        }

        File destination = new File(DESTINATION_DIRECTORY, "module");
        destination.mkdirs();

        List<String> options = new ArrayList<>(Arrays.asList(
            "--release", String.valueOf(release), "-Xlint:-options", "-d", destination.getAbsolutePath(),
            "--module-path", modulePath.toString(), "--patch-module", matcher.group(1) + '=' + moduleContent.getAbsolutePath()));

        return compile(compiler, options, Arrays.<JavaFileObject>asList(new JavaSourceFileObject("module-info", source)));
    }

    protected static boolean compile(JavaCompiler compiler, List<String> options, List<JavaFileObject> compilationUnits) throws Exception {
        boolean compilationSuccess = false;
        StringWriter writer = new StringWriter();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

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

    /** The directory of the class files of the last compilation */
    public static File getDestinationDirectory() {
        return DESTINATION_DIRECTORY;
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
