package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.jd.core.v1.loader.DirectoryLoader;
import org.jd.core.v1.model.message.Message;
import org.jd.core.v1.printer.PlainTextPrinter;
import org.jd.core.v1.service.converter.classfiletojavasyntax.ClassFileToJavaSyntaxProcessor;
import org.jd.core.v1.service.deserializer.classfile.DeserializeClassFileProcessor;
import org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.JavaSyntaxToJavaFragmentProcessor;
import org.jd.core.v1.service.layouter.LayoutFragmentProcessor;
import org.jd.core.v1.service.tokenizer.javafragmenttotoken.JavaFragmentToTokenProcessor;
import org.jd.core.v1.service.writer.WriteTokenProcessor;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Classes compiled without debug information ('-g:none'): no LocalVariableTable, the types of the local
 * variables are inferred while the byte code is parsed. The samples of the other tests have debug information.
 */
public class JavaNoDebugInfoTest {
    @Test
    public void iincOnUndecidedVariable() throws Exception {
        // 'int s = 0': '0' may still be 'false' when 'iinc s 10' is parsed, the increment was printed 'true'
        String source = decompileWithoutDebugInfo("p/Iinc",
            "package p;\n" +
            "public class Iinc {\n" +
            "    public int add(int v) { int s = 0; s += 10; s -= 3; return s + v; }\n" +
            "    public int loop(int[] a) { int s = 0; for (int v : a) { switch (v) { case 0: continue; case 1: s += 10; break; default: s += v; } s++; } return s; }\n" +
            "}\n");

        assertFalse(source.contains("true"), "the increment of 'iinc' is printed as a boolean");
        assertTrue(source.matches("(?s).*\\+= 10;.*-= 3;.*"));
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/Iinc", source)));
    }

    protected String decompileWithoutDebugInfo(String internalName, String javaSource) throws Exception {
        File directory = Files.createTempDirectory("jd-core-no-debug-info").toFile();
        File sourceFile = new File(directory, internalName + ".java");

        sourceFile.getParentFile().mkdirs();
        Files.write(sourceFile.toPath(), javaSource.getBytes("UTF-8"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(Collections.singletonList(sourceFile));
            boolean compiled = compiler.getTask(null, fileManager, null, Arrays.asList("-g:none", "--release", "8", "-Xlint:-options", "-d", directory.getPath()), null, units).call();

            assertTrue(compiled, "the test source does not compile");
        }

        PlainTextPrinter printer = new PlainTextPrinter();
        Message message = new Message();
        message.setHeader("loader", new DirectoryLoader(directory));
        message.setHeader("printer", printer);
        message.setHeader("mainInternalTypeName", internalName);
        message.setHeader("configuration", Collections.emptyMap());

        new DeserializeClassFileProcessor().process(message);
        new ClassFileToJavaSyntaxProcessor().process(message);
        new JavaSyntaxToJavaFragmentProcessor().process(message);
        new LayoutFragmentProcessor().process(message);
        new JavaFragmentToTokenProcessor().process(message);
        new WriteTokenProcessor().process(message);

        String source = printer.toString();
        System.out.println(source);
        return source;
    }
}
