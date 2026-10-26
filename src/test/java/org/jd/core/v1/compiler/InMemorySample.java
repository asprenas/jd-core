package org.jd.core.v1.compiler;

import org.jd.core.v1.loader.DirectoryLoader;
import org.jd.core.v1.model.message.Message;
import org.jd.core.v1.printer.PlainTextPrinter;
import org.jd.core.v1.service.converter.classfiletojavasyntax.ClassFileToJavaSyntaxProcessor;
import org.jd.core.v1.service.deserializer.classfile.DeserializeClassFileProcessor;
import org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.JavaSyntaxToJavaFragmentProcessor;
import org.jd.core.v1.service.layouter.LayoutFragmentProcessor;
import org.jd.core.v1.service.tokenizer.javafragmenttotoken.JavaFragmentToTokenProcessor;
import org.jd.core.v1.service.writer.WriteTokenProcessor;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A sample compiled while the test runs, for the cases that the sample zips do not have (javac options, small sources).
 */
public final class InMemorySample {
    private final File directory;

    /**
     * @param sources internal type name -> Java source
     * @param options javac options ('-g:none', '--release', '8', ...), '-d' is added
     */
    public InMemorySample(Map<String, String> sources, String... options) throws Exception {
        directory = Files.createTempDirectory("jd-core-sample").toFile();

        List<File> files = new ArrayList<>();

        for (Map.Entry<String, String> entry : sources.entrySet()) {
            File file = new File(directory, entry.getKey() + ".java");
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), entry.getValue().getBytes(StandardCharsets.UTF_8));
            files.add(file);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<String> allOptions = new ArrayList<>(Arrays.asList(options));

        allOptions.addAll(Arrays.asList("-d", directory.getPath()));

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(files);

            if (!compiler.getTask(null, fileManager, null, allOptions, null, units).call()) {
                throw new IllegalStateException("the sample does not compile");
            }
        }
    }

    /** The directory of the compiled class files */
    public File getDirectory() {
        return directory;
    }

    public String decompile(String internalTypeName, Map<String, Object> configuration) throws Exception {
        PlainTextPrinter printer = new PlainTextPrinter();
        Message message = new Message();
        message.setHeader("loader", new DirectoryLoader(directory));
        message.setHeader("printer", printer);
        message.setHeader("mainInternalTypeName", internalTypeName);
        message.setHeader("configuration", configuration);

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
