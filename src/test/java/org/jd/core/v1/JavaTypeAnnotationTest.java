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

    /** Remaining item C3: explicit type arguments of method invocations (0x49) */
    @Test
    public void methodInvocationTypeArguments() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/A",
            "package p;\n" +
            "import java.lang.annotation.*;\n" +
            "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) public @interface A {}\n");
        sources.put("p/Args",
            "package p;\n" +
            "import java.util.*;\n" +
            "public class Args {\n" +
            "    <T> T id(T t) { return t; }\n" +
            "    static <K, V> Map<K, V> map() { return new HashMap<>(); }\n" +
            "    public String instance() {\n" +
            "        return this.<@A String>id(\"x\");\n" +
            "    }\n" +
            "    public Map<String, Integer> both() {\n" +
            "        return Args.<String, @A Integer>map();\n" +
            "    }\n" +
            "    public List<String> other() {\n" +
            "        return Collections.<@A String>emptyList();\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "11");
        String source = sample.decompile("p/Args", Collections.emptyMap());

        assertTrue(source.contains("this.<@A String>id(\"x\")"), "instance method");
        assertTrue(source.contains("Args.<String, @A Integer>map()"), "second type argument");
        assertTrue(source.contains("Collections.<@A String>emptyList()"), "method of another class");
        assertTrue(CompilerUtil.compile("11",
            new JavaSourceFileObject("p/A", sources.get("p/A")),
            new JavaSourceFileObject("p/Args", source)), "the decompiled source does not recompile");
    }

    /** Remaining item C3: type arguments of constructor invocations (0x48) and references (0x4A, 0x4B) */
    @Test
    public void constructorAndReferenceTypeArguments() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/A",
            "package p;\n" +
            "import java.lang.annotation.*;\n" +
            "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) public @interface A {}\n");
        sources.put("p/G",
            "package p;\n" +
            "import java.util.Date;\n" +
            "import java.util.function.*;\n" +
            "public class G {\n" +
            "    <T> G(T t) {}\n" +
            "    G() {}\n" +
            "    static <T> T id(T t) { return t; }\n" +
            "    public G make() {\n" +
            "        return new <@A String>G(\"x\");\n" +
            "    }\n" +
            "    public G plain() {\n" +
            "        return new G(\"y\");\n" +
            "    }\n" +
            "    public G makeDate(Date d) {\n" +
            "        return new <@A Date>G(d);\n" +
            "    }\n" +
            "    public Function<String, G> constructorReference() {\n" +
            "        return G::<@A String>new;\n" +
            "    }\n" +
            "    public Function<String, String> methodReference() {\n" +
            "        return G::<@A String>id;\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "11");
        String source = sample.decompile("p/G", Collections.emptyMap());

        assertTrue(source.contains("new <@A String>G(\"x\")"), "constructor invocation");
        assertTrue(source.contains("new <@A Date>G(d)"), "constructor invocation, imported type");
        assertTrue(source.contains("new G(\"y\")"), "generic constructor without cast to its type parameter");
        assertTrue(source.contains("G::<@A String>new"), "constructor reference");
        assertTrue(source.contains("G::<@A String>id"), "method reference");
        assertTrue(CompilerUtil.compile("11",
            new JavaSourceFileObject("p/A", sources.get("p/A")),
            new JavaSourceFileObject("p/G", source)), "the decompiled source does not recompile");
    }

    /** Remaining item C6: upcasts consumed by 'areturn', 'putfield' and invocations */
    @Test
    public void upcasts() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/A",
            "package p;\n" +
            "import java.lang.annotation.*;\n" +
            "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) public @interface A {}\n");
        sources.put("p/Upcasts",
            "package p;\n" +
            "public class Upcasts {\n" +
            "    Object field;\n" +
            "    public Object returned(String s) {\n" +
            "        return (@A Object)s;\n" +
            "    }\n" +
            "    public void stored(String s) {\n" +
            "        this.field = (@A Object)s;\n" +
            "    }\n" +
            "    public void passed(String s) {\n" +
            "        use((@A Object)s);\n" +
            "    }\n" +
            "    void use(Object o) {}\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "11");
        String source = sample.decompile("p/Upcasts", Collections.emptyMap());

        assertTrue(source.contains("return (@A Object)s;"), "return");
        assertTrue(source.contains("this.field = (@A Object)s;"), "field");
        assertTrue(source.contains("use((@A Object)s);"), "argument");
        assertTrue(CompilerUtil.compile("11",
            new JavaSourceFileObject("p/A", sources.get("p/A")),
            new JavaSourceFileObject("p/Upcasts", source)), "the decompiled source does not recompile");
    }

    /** Remaining item C5: non-static inner types ('type_path' kind 1) */
    @Test
    public void nonStaticInnerType() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/A",
            "package p;\n" +
            "import java.lang.annotation.*;\n" +
            "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) public @interface A {}\n");
        sources.put("p/B",
            "package p;\n" +
            "import java.lang.annotation.*;\n" +
            "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) public @interface B {}\n");
        sources.put("p/Outer",
            "package p;\n" +
            "public class Outer {\n" +
            "    public class Inner {}\n" +
            "    public static class Nested {}\n" +
            "}\n");
        sources.put("p/User",
            "package p;\n" +
            "public class User {\n" +
            "    Outer.@A Inner inner;\n" +
            "    @B Outer.@A Inner both;\n" +
            "    @B Outer.Inner outerOnly;\n" +
            "    Outer.@A Nested nested;\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "11");
        String source = sample.decompile("p/User", Collections.emptyMap());

        assertTrue(source.contains("Outer.@A Inner inner;"), "inner type");
        assertTrue(source.contains("@B Outer.@A Inner both;"), "outer and inner types");
        assertTrue(source.contains("@B Outer.Inner outerOnly;"), "outer type only");
        assertTrue(source.contains("Outer.@A Nested nested;"), "static nested type");
        assertTrue(CompilerUtil.compile("11",
            new JavaSourceFileObject("p/A", sources.get("p/A")),
            new JavaSourceFileObject("p/B", sources.get("p/B")),
            new JavaSourceFileObject("p/Outer", sources.get("p/Outer")),
            new JavaSourceFileObject("p/User", source)), "the decompiled source does not recompile");
    }

    /** Remaining item C4: receiver parameters (0x15) */
    @Test
    public void receiverParameter() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/A",
            "package p;\n" +
            "import java.lang.annotation.*;\n" +
            "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) public @interface A {}\n");
        sources.put("p/Receiver",
            "package p;\n" +
            "public class Receiver {\n" +
            "    public int none(@A Receiver this) {\n" +
            "        return 1;\n" +
            "    }\n" +
            "    public int one(@A Receiver this, int i) {\n" +
            "        return i;\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "11");
        String source = sample.decompile("p/Receiver", Collections.emptyMap());

        assertTrue(source.contains("none(@A Receiver this)"), "receiver only");
        assertTrue(source.contains("one(@A Receiver this, int i)"), "receiver and parameter");
        assertTrue(CompilerUtil.compile("11",
            new JavaSourceFileObject("p/A", sources.get("p/A")),
            new JavaSourceFileObject("p/Receiver", source)), "the decompiled source does not recompile");
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
