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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

        assertFalse(source.contains("// Byte code:"), internalName + ": decompiler fell back to byte code output");

        // Not a pass: the recompilation check cannot run on an older JDK, report the test as skipped.
        assumeTrue(runtimeFeatureVersion() >= version, internalName + ": recompilation needs a JDK " + version + "+");

        String release = (version == 8) ? "1.8" : String.valueOf(version);

        assertTrue(CompilerUtil.compile(release, new JavaSourceFileObject(internalName, source)),
            internalName + ": decompiled source does not recompile (see standard error)");
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
        Message message = new Message();
        message.setHeader("loader", loader);
        message.setHeader("printer", printer);
        message.setHeader("mainInternalTypeName", internalTypeName);
        message.setHeader("configuration", Collections.emptyMap());

        new DeserializeClassFileProcessor().process(message);
        new ClassFileToJavaSyntaxProcessor().process(message);
        new JavaSyntaxToJavaFragmentProcessor().process(message);
        new LayoutFragmentProcessor().process(message);
        new JavaFragmentToTokenProcessor().process(message);
        new WriteTokenProcessor().process(message);

        return printer.toString();
    }
}
