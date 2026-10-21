package org.jd.core.v1;

import org.jd.core.v1.api.loader.Loader;
import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.jd.core.v1.loader.ZipLoader;
import org.jd.core.v1.model.message.Message;
import org.jd.core.v1.printer.PlainTextPrinter;
import org.jd.core.v1.service.converter.classfiletojavasyntax.ClassFileToJavaSyntaxProcessor;
import org.jd.core.v1.service.deserializer.classfile.DeserializeClassFileProcessor;
import org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.JavaSyntaxToJavaFragmentProcessor;
import org.jd.core.v1.service.layouter.LayoutFragmentProcessor;
import org.jd.core.v1.service.tokenizer.javafragmenttotoken.JavaFragmentToTokenProcessor;
import org.jd.core.v1.service.writer.WriteTokenProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Feature-implementation tests for the per-version Java samples (Java 8, 11, 17, 21, 25).
 *
 * Every top-level class found in src/test/resources/zip/data-java-language-N.zip (compiled by JDK N) and
 * data-java-language-N-javac25.zip (compiled by JDK 25 with --release N) becomes one
 * test case: it is decompiled, must not fall back to "// Byte code:" output, and the decompiled
 * source must recompile. The zips are produced by ./gradlew buildJavaLanguageSamples (Temurin toolchains)
 * from src/test/resources/java/org/jd/core/test/java<N>/.
 *
 * jd-core does not support all of these language features yet, so the tests are OFF by default
 * and do not affect ./gradlew test. Enable them with the JDCORE_SAMPLES environment variable:
 *
 *   JDCORE_SAMPLES=all ./gradlew test --tests org.jd.core.v1.JavaLanguageVersionTest
 *   JDCORE_SAMPLES=11,17 ./gradlew test --tests org.jd.core.v1.JavaLanguageVersionTest
 */
@EnabledIfEnvironmentVariable(named = "JDCORE_SAMPLES", matches = ".*\\S.*")
public class JavaLanguageVersionTest {
    private static final int[] VERSIONS = {8, 11, 17, 21, 25};
    private static final int NEWEST_JDK = 25;
    private static final Map<String, ZipLoader> LOADERS = new HashMap<>();

    static List<Arguments> samples() throws Exception {
        List<Arguments> samples = new ArrayList<>();
        Set<Integer> selected = parseSelection(System.getenv("JDCORE_SAMPLES"));

        for (int version : VERSIONS) {
            if (!selected.contains(version)) {
                continue;
            }

            // data-java-language-N.zip: compiled by JDK N, -javacM: compiled by the newest JDK M with --release N
            for (String variant : new String[] {"", "-javac" + NEWEST_JDK}) {
                if (!variant.isEmpty() && version == NEWEST_JDK) {
                    continue;
                }

                String resource = "/zip/data-java-language-" + version + variant + ".zip";
                ZipLoader loader = loader(resource);

                for (String entry : new TreeSet<>(loader.getMap().keySet())) {
                    if (entry.endsWith(".class") && entry.indexOf('$') == -1 && !entry.equals("module-info.class")) {
                        String internalName = entry.substring(0, entry.length() - ".class".length());
                        String displayName = internalName.substring(internalName.lastIndexOf('/') + 1) + variant;
                        samples.add(Arguments.of(version, resource, internalName, displayName));
                    }
                }
            }
        }

        return samples;
    }

    @ParameterizedTest(name = "java{0} {3}")
    @MethodSource("samples")
    public void decompileAndRecompile(int version, String resource, String internalName, String displayName) throws Exception {
        Loader loader = loader(resource);
        PlainTextPrinter printer = new PlainTextPrinter();
        String source = decompile(loader, printer, internalName);

        System.out.println("- - - - - - - - ");
        System.out.println(source);
        System.out.println("- - - - - - - - ");

        // Every check runs and the test reports all problems at once, so one failure does not hide another
        // (e.g. a byte code fallback hiding a switch expression that lost its values).
        List<String> problems = new ArrayList<>();
        List<String> details = new ArrayList<>();

        boolean fallback = source.contains("// Byte code:");
        if (fallback) {
            problems.add("decompiler fell back to byte code output");
        }

        // 1. Output patterns: results that recompile but are wrong
        List<String> violations = checkExpectations(version, internalName, source);
        if (!violations.isEmpty()) {
            problems.add("output does not match " + expectResource(version, internalName));
            details.addAll(violations);
        }

        // 2. Recompilation, 3. same results as the original class
        if (!fallback) {
            if (runtimeFeatureVersion() < version) {
                // Not a pass: the recompilation check cannot run on an older JDK, report the test as skipped.
                assumeTrue(!problems.isEmpty(), internalName + ": recompilation needs a JDK " + version + "+");
            } else {
                String release = (version == 8) ? "1.8" : String.valueOf(version);

                if (!CompilerUtil.compile(release, new JavaSourceFileObject(internalName, source))) {
                    problems.add("decompiled source does not recompile (see standard error)");
                } else {
                    List<String> differences = RunComparison.compare(runResource(version, internalName), (ZipLoader)loader, internalName);
                    if (!differences.isEmpty()) {
                        problems.add("recompiled class behaves differently, see " + runResource(version, internalName));
                        details.addAll(differences);
                    }
                }
            }
        }

        if (!problems.isEmpty()) {
            StringBuilder message = new StringBuilder(internalName).append(": ").append(String.join("; ", problems));
            for (String detail : details) {
                message.append("\n  ").append(detail);
            }
            fail(message.toString());
        }
    }

    /** Phase 4-3: 'textBlocks' configuration, the text blocks must have the values of the original strings */
    @ParameterizedTest(name = "java17 TextBlocks{0} textBlocks=true")
    @ValueSource(strings = {"", "-javac" + NEWEST_JDK})
    public void textBlocksConfiguration(String variant) throws Exception {
        assumeTrue(parseSelection(System.getenv("JDCORE_SAMPLES")).contains(17), "java17 samples not selected");
        assumeTrue(runtimeFeatureVersion() >= 17, "recompilation needs a JDK 17+");

        String internalName = "org/jd/core/test/java17/TextBlocks";
        Loader loader = loader("/zip/data-java-language-17" + variant + ".zip");
        String source = decompile(loader, new PlainTextPrinter(), internalName, Collections.singletonMap("textBlocks", "true"));

        System.out.println("- - - - - - - - ");
        System.out.println(source);
        System.out.println("- - - - - - - - ");

        assertTrue(source.contains("\"\"\"\n<html>\n    <body>\n"), "html() is not a text block");
        assertTrue(source.contains("keep \\s\n"), "trailing spaces are not kept");
        assertTrue(source.contains("\"line1\\nline2\""), "a string without final line break is not a text block");
        assertTrue(CompilerUtil.compile("17", new JavaSourceFileObject(internalName, source)), "decompiled source does not recompile");

        List<String> differences = RunComparison.compare(runResource(17, internalName), (ZipLoader)loader, internalName);
        assertTrue(differences.isEmpty(), String.join("\n", differences));
    }

    /** Phase 4-5: 'unnamedVariables' configuration, '_' only for the variables that are not read */
    @Test
    public void unnamedVariablesConfiguration() throws Exception {
        assumeTrue(parseSelection(System.getenv("JDCORE_SAMPLES")).contains(25), "java25 samples not selected");
        assumeTrue(runtimeFeatureVersion() >= 25, "recompilation needs a JDK 25+");

        String internalName = "org/jd/core/test/java25/UnnamedVariables";
        Loader loader = loader("/zip/data-java-language-25.zip");
        String source = decompile(loader, new PlainTextPrinter(), internalName, Collections.singletonMap("unnamedVariables", "true"));

        System.out.println("- - - - - - - - ");
        System.out.println(source);
        System.out.println("- - - - - - - - ");

        assertTrue(source.matches("(?s).*catch \\(NumberFormatException _\\).*"), "unread catch parameter");
        assertTrue(source.matches("(?s).*for \\(String _ : list\\).*"), "unread for-each variable");
        assertTrue(source.matches("(?s).*long _ = System\\.nanoTime\\(\\);.*"), "unread local variable");
        assertTrue(source.matches("(?s).*\\(a, _\\) -> a.*"), "unread lambda parameter");
        assertTrue(source.matches("(?s).*int n = 0;.*n\\+\\+.*"), "a read variable keeps its name");
        assertTrue(CompilerUtil.compile("25", new JavaSourceFileObject(internalName, source)), "decompiled source does not recompile");

        List<String> differences = RunComparison.compare(runResource(25, internalName), (ZipLoader)loader, internalName);
        assertTrue(differences.isEmpty(), String.join("\n", differences));
    }

    private static String runResource(int version, String internalName) {
        return "/java/org/jd/core/test/java" + version + "/" + internalName.substring(internalName.lastIndexOf('/') + 1) + ".run";
    }

    /**
     * Optional expectations next to the sample source: java&lt;N&gt;/&lt;Sample&gt;.expect, one rule per line,
     * '#' starts a comment.
     *
     *   + text     the output must contain text
     *   - text     the output must not contain text
     *   ~ regex    the output must contain a match of regex
     *
     * The same file applies to every compiler variant of the sample (data-java-language-N.zip, -javacM.zip).
     */
    private static List<String> checkExpectations(int version, String internalName, String source) throws Exception {
        List<String> violations = new ArrayList<>();
        String resource = expectResource(version, internalName);

        try (InputStream is = JavaLanguageVersionTest.class.getResourceAsStream(resource)) {
            if (is == null) {
                return violations;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (line.length() < 3 || line.charAt(1) != ' ') {
                    throw new IllegalArgumentException(resource + ": invalid rule '" + line + "'");
                }

                String value = line.substring(2);

                switch (line.charAt(0)) {
                    case '+':
                        if (!source.contains(value)) {
                            violations.add("missing:   " + value);
                        }
                        break;
                    case '-':
                        if (source.contains(value)) {
                            violations.add("unwanted:  " + value);
                        }
                        break;
                    case '~':
                        if (!Pattern.compile(value).matcher(source).find()) {
                            violations.add("no match:  " + value);
                        }
                        break;
                    default:
                        throw new IllegalArgumentException(resource + ": invalid rule '" + line + "'");
                }
            }
        }

        return violations;
    }

    private static String expectResource(int version, String internalName) {
        return "/java/org/jd/core/test/java" + version + "/" + internalName.substring(internalName.lastIndexOf('/') + 1) + ".expect";
    }

    private static int runtimeFeatureVersion() {
        String javaVersion = System.getProperty("java.version");

        if (javaVersion.startsWith("1.")) {
            return Integer.parseInt(javaVersion.substring(2, 3));
        }

        int end = 0;

        while (end < javaVersion.length() && Character.isDigit(javaVersion.charAt(end))) {
            end++;
        }

        return Integer.parseInt(javaVersion.substring(0, end));
    }

    private static synchronized ZipLoader loader(String resource) throws Exception {
        ZipLoader loader = LOADERS.get(resource);

        if (loader == null) {
            try (InputStream is = JavaLanguageVersionTest.class.getResourceAsStream(resource)) {
                if (is == null) {
                    throw new IllegalStateException(resource + " not found, run ./gradlew buildJavaLanguageSamples first");
                }
                loader = new ZipLoader(is);
            }
            LOADERS.put(resource, loader);
        }

        return loader;
    }

    private static Set<Integer> parseSelection(String value) {
        Set<Integer> selected = new HashSet<>();

        if (value == null || value.trim().isEmpty()) {
            return selected;
        }

        String trimmed = value.trim();

        if (trimmed.equalsIgnoreCase("all") || trimmed.equals("1")) {
            for (int version : VERSIONS) {
                selected.add(version);
            }
        } else {
            for (String part : trimmed.split(",")) {
                selected.add(Integer.parseInt(part.trim()));
            }
        }

        return selected;
    }

    private static String decompile(Loader loader, PlainTextPrinter printer, String internalTypeName) throws Exception {
        return decompile(loader, printer, internalTypeName, Collections.emptyMap());
    }

    private static String decompile(Loader loader, PlainTextPrinter printer, String internalTypeName, Map<String, Object> configuration) throws Exception {
        Message message = new Message();
        message.setHeader("loader", loader);
        message.setHeader("printer", printer);
        message.setHeader("mainInternalTypeName", internalTypeName);
        message.setHeader("configuration", configuration);

        new DeserializeClassFileProcessor().process(message);
        new ClassFileToJavaSyntaxProcessor().process(message);
        new JavaSyntaxToJavaFragmentProcessor().process(message);
        new LayoutFragmentProcessor().process(message);
        new JavaFragmentToTokenProcessor().process(message);
        new WriteTokenProcessor().process(message);

        return printer.toString();
    }
}
