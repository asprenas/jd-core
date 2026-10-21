package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Type annotations of the types written with their package name ('java.sql.@A Date') */
public class JavaTypeAnnotationTest {
    @Test
    public void qualifiedTypeName() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/A",
            "package p;\n" +
            "import java.lang.annotation.*;\n" +
            "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) public @interface A {}\n");
        sources.put("p/Dates",
            "package p;\n" +
            "public class Dates {\n" +
            "    java.util.@A Date utilDate;\n" +
            "    java.sql.@A Date sqlDate;\n" +
            "    public java.sql.@A Date convert(java.util.@A Date d) { return new java.sql.Date(d.getTime()); }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");
        String source = sample.decompile("p/Dates", Collections.emptyMap());

        assertFalse(source.contains("@A java."), "an annotation before a package name");
        assertTrue(source.matches("(?s).*java\\.(util|sql)\\.@A Date.*"), "the annotation of the qualified type is before the simple name");
        assertTrue(CompilerUtil.compile("1.8",
            new JavaSourceFileObject("p/A", sources.get("p/A")),
            new JavaSourceFileObject("p/Dates", source)), "the decompiled source does not recompile");
    }

    /** Remaining items C1, C2: resource variables (0x41) and exception parameters (0x42) */
    @Test
    public void resourceAndCatch() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/A",
            "package p;\n" +
            "import java.lang.annotation.*;\n" +
            "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) public @interface A {}\n");
        sources.put("p/Body",
            "package p;\n" +
            "import java.io.*;\n" +
            "public class Body {\n" +
            "    public int read(String path) {\n" +
            "        try (@A Reader reader = new FileReader(path)) {\n" +
            "            return reader.read();\n" +
            "        } catch (@A IOException e) {\n" +
            "            return -1;\n" +
            "        }\n" +
            "    }\n" +
            "    public int parse(String s) {\n" +
            "        try {\n" +
            "            return Integer.parseInt(s);\n" +
            "        } catch (@A NumberFormatException e) {\n" +
            "            return e.getMessage().length();\n" +
            "        }\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "11");
        String source = sample.decompile("p/Body", Collections.emptyMap());

        assertTrue(source.contains("try (@A Reader reader = new FileReader(path))"), "resource variable");
        assertTrue(source.contains("catch (@A IOException e)"), "exception parameter");
        assertTrue(source.contains("catch (@A NumberFormatException e)"), "exception parameter");
        assertTrue(CompilerUtil.compile("11",
            new JavaSourceFileObject("p/A", sources.get("p/A")),
            new JavaSourceFileObject("p/Body", source)), "the decompiled source does not recompile");
    }

    /** Remaining item C3: 'instanceof' (0x43) and 'new' (0x44) */
    @Test
    public void instanceOfAndNew() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/A",
            "package p;\n" +
            "import java.lang.annotation.*;\n" +
            "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) public @interface A {}\n");
        sources.put("p/Body",
            "package p;\n" +
            "import java.util.*;\n" +
            "public class Body {\n" +
            "    public boolean test(Object o) {\n" +
            "        return o instanceof @A String;\n" +
            "    }\n" +
            "    public List<String> make() {\n" +
            "        List<String> list = new @A ArrayList<>();\n" +
            "        list.add(new @A StringBuilder(\"x\").toString());\n" +
            "        return list;\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "11");
        String source = sample.decompile("p/Body", Collections.emptyMap());

        assertTrue(source.contains("o instanceof @A String"), "instanceof");
        assertTrue(source.contains("new @A ArrayList<>()"), "new with diamond");
        assertTrue(source.contains("new @A StringBuilder(\"x\")"), "new");
        assertTrue(CompilerUtil.compile("11",
            new JavaSourceFileObject("p/A", sources.get("p/A")),
            new JavaSourceFileObject("p/Body", source)), "the decompiled source does not recompile");
    }

    /** Remaining item C3: constructor (0x45) and method (0x46) references */
    @Test
    public void methodReferences() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/A",
            "package p;\n" +
            "import java.lang.annotation.*;\n" +
            "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) public @interface A {}\n");
        sources.put("p/Refs",
            "package p;\n" +
            "import java.util.function.*;\n" +
            "public class Refs {\n" +
            "    public Supplier<Object> supplier() {\n" +
            "        return @A StringBuilder::new;\n" +
            "    }\n" +
            "    public Function<Object, String> function() {\n" +
            "        return @A String::valueOf;\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "11");
        String source = sample.decompile("p/Refs", Collections.emptyMap());

        assertTrue(source.contains("@A StringBuilder::new"), "constructor reference");
        assertTrue(source.contains("@A String::valueOf"), "method reference");
        assertTrue(CompilerUtil.compile("11",
            new JavaSourceFileObject("p/A", sources.get("p/A")),
            new JavaSourceFileObject("p/Refs", source)), "the decompiled source does not recompile");
    }
}
