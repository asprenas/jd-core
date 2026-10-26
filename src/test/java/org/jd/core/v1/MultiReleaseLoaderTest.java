package org.jd.core.v1;

import org.jd.core.v1.api.loader.MultiReleaseLoader;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.loader.ZipLoader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Multi-release jar (JEP 238): the class of the highest version lower than or equal to the target is decompiled */
public class MultiReleaseLoaderTest {
    @Test
    public void classOfTheTargetVersion() throws Exception {
        ZipLoader zipLoader = jar();

        assertTrue(decompile(zipLoader, 8, "p/A").contains("\"root\""));
        assertTrue(decompile(zipLoader, 10, "p/A").contains("\"root\""));
        assertTrue(decompile(zipLoader, 11, "p/A").contains("\"v11\""));
        assertTrue(decompile(zipLoader, 16, "p/A").contains("\"v11\""));
        assertTrue(decompile(zipLoader, 17, "p/A").contains("\"v17\""));
        assertTrue(decompile(zipLoader, 25, "p/A").contains("\"v17\""));
    }

    @Test
    public void choiceForEachClass() throws Exception {
        ZipLoader zipLoader = jar();
        MultiReleaseLoader loader = new MultiReleaseLoader(zipLoader, 17);

        // 'p/B' exists only at the root
        assertTrue(loader.canLoad("p/B"));
        assertArrayEquals(zipLoader.load("p/B"), loader.load("p/B"));
        // 'p/A' comes from 'META-INF/versions/17/'
        assertArrayEquals(zipLoader.load("META-INF/versions/17/p/A"), loader.load("p/A"));
        assertFalse(loader.canLoad("p/Missing"));
        assertNull(loader.load("p/Missing"));
    }

    @Test
    public void outerClassVersionedAndInnerClassAtTheRoot() throws Exception {
        // Version 11 of 'p/A' has an inner class 'p/A$In' only in the root sample: it is read from the root
        String source = decompile(jar(), 11, "p/A");

        assertTrue(source.contains("class In"), source);
        assertTrue(source.contains("\"inner\""), source);
    }

    @Test
    public void sealedSuperTypeOfTheTargetVersion() throws Exception {
        // 'p/T' exists only at the root, 'p/S' is sealed only in version 17: 'non-sealed' comes from the super type
        // read by jd-core while decompiling 'p/T', from the directory of the target version
        String source17 = decompile(jar(), 17, "p/T");
        String source8 = decompile(jar(), 8, "p/T");

        assertTrue(source17.contains("non-sealed class T"), source17);
        assertFalse(source8.contains("sealed"), source8);
    }

    protected static String decompile(ZipLoader zipLoader, int targetVersion, String internalTypeName) throws Exception {
        return InMemorySample.decompile(new MultiReleaseLoader(zipLoader, targetVersion), internalTypeName, Collections.emptyMap());
    }

    protected static ZipLoader jar() throws Exception {
        Map<String, String> root = new LinkedHashMap<>();
        root.put("p/A", "package p; public class A { public String v() { return \"root\"; } static class In { String s() { return \"inner\"; } } }\n");
        root.put("p/B", "package p; public class B {}\n");
        root.put("p/S", "package p; public abstract class S {}\n");
        root.put("p/T", "package p; public class T extends S {}\n");

        // The inner class 'In' is declared by the source, but its class file is not added to the versioned directory
        Map<String, String> v11 = new LinkedHashMap<>();
        v11.put("p/A", "package p; public class A { public String v() { return \"v11\"; } static class In { String s() { return \"inner\"; } } }\n");

        Map<String, String> v17 = new LinkedHashMap<>();
        v17.put("p/A", "package p; public class A { public String v() { return \"v17\"; } }\n");
        v17.put("p/S", "package p; public abstract sealed class S permits T {}\n");
        v17.put("p/T", "package p; public non-sealed class T extends S {}\n");

        Map<String, InMemorySample> samples = new LinkedHashMap<>();
        samples.put("", new InMemorySample(root, "--release", "8", "-Xlint:-options"));
        InMemorySample sample11 = new InMemorySample(v11, "--release", "11", "-Xlint:-options");
        assertTrue(new java.io.File(sample11.getDirectory(), "p/A$In.class").delete());
        samples.put("META-INF/versions/11/", sample11);
        InMemorySample sample17 = new InMemorySample(v17, "--release", "17");
        assertTrue(new java.io.File(sample17.getDirectory(), "p/T.class").delete());
        samples.put("META-INF/versions/17/", sample17);

        return new ZipLoader(new ByteArrayInputStream(InMemorySample.zip(samples)));
    }
}
