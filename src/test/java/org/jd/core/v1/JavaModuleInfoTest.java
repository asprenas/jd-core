package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.jd.core.v1.loader.DirectoryLoader;
import org.jd.core.v1.model.classfile.ClassFile;
import org.jd.core.v1.model.classfile.attribute.AttributeModule;
import org.jd.core.v1.model.classfile.attribute.ModuleInfo;
import org.jd.core.v1.model.classfile.attribute.PackageInfo;
import org.jd.core.v1.model.classfile.attribute.ServiceInfo;
import org.jd.core.v1.model.message.Message;
import org.jd.core.v1.service.deserializer.classfile.DeserializeClassFileProcessor;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 'module-info.class': the decompiled source is recompiled and gives the same 'Module' attribute, versions excepted */
public class JavaModuleInfoTest {
    @Test
    public void openModuleWithAllDirectives() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("module-info",
            "@Deprecated\n" +
            "open module m.a {\n" +
            "    requires transitive java.sql;\n" +
            "    requires static java.compiler;\n" +
            "    requires static transitive java.logging;\n" +
            "    exports p.a;\n" +
            "    exports p.b to java.base, java.sql;\n" +
            "    uses p.a.Service;\n" +
            "    provides p.a.Service with p.b.Impl, p.b.Impl2;\n" +
            "}\n");
        sources.put("p/a/Service", "package p.a; public interface Service {}\n");
        sources.put("p/b/Impl", "package p.b; public class Impl implements p.a.Service {}\n");
        sources.put("p/b/Impl2", "package p.b; public class Impl2 implements p.a.Service {}\n");

        String source = check(sources);

        assertTrue(source.contains("@Deprecated"), source);
        assertTrue(source.contains("open module m.a"), source);
        assertTrue(source.contains("requires static transitive java.logging;"), source);
        // 'requires java.base' is added by javac (ACC_MANDATED)
        assertFalse(source.contains("java.base;"), source);
    }

    @Test
    public void moduleWithOpens() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("module-info",
            "module m.b {\n" +
            "    requires java.sql;\n" +
            "    exports p.c;\n" +
            "    opens p.c;\n" +
            "    opens p.d to java.sql;\n" +
            "}\n");
        sources.put("p/c/C", "package p.c; public class C {}\n");
        sources.put("p/d/D", "package p.d; public class D {}\n");

        String source = check(sources);

        assertTrue(source.contains("opens p.d to java.sql;"), source);
    }

    /** Decompile and recompile all classes, compare the 'Module' attributes, return the decompiled 'module-info' */
    protected static String check(Map<String, String> sources) throws Exception {
        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "17");
        String expected = describe(readModule(sample.getDirectory()));
        List<JavaFileObject> files = new ArrayList<>();
        String moduleInfo = null;

        for (String internalTypeName : sources.keySet()) {
            String source = sample.decompile(internalTypeName, Collections.emptyMap());
            files.add(new JavaSourceFileObject(internalTypeName, source));

            if (internalTypeName.equals("module-info")) {
                moduleInfo = source;
            }
        }

        assertTrue(CompilerUtil.compile("17", files.toArray(new JavaFileObject[0])), moduleInfo);
        assertEquals(expected, describe(readModule(CompilerUtil.getDestinationDirectory())), moduleInfo);

        return moduleInfo;
    }

    protected static AttributeModule readModule(File directory) throws Exception {
        Message message = new Message();
        message.setHeader("loader", new DirectoryLoader(directory));
        message.setHeader("mainInternalTypeName", "module-info");
        new DeserializeClassFileProcessor().process(message);
        return ((ClassFile)message.getBody()).getAttribute("Module");
    }

    /** Name, flags and directives, without the versions */
    protected static String describe(AttributeModule module) {
        StringBuilder sb = new StringBuilder();
        sb.append(module.getName()).append(' ').append(module.getFlags()).append('\n');

        TreeSet<String> lines = new TreeSet<>();
        if (module.getRequires() != null) {
            for (ModuleInfo mi : module.getRequires()) lines.add("requires " + mi.getName() + ' ' + mi.getFlags());
        }
        describe(lines, "exports", module.getExports());
        describe(lines, "opens", module.getOpens());
        if (module.getUses() != null) {
            for (String use : module.getUses()) lines.add("uses " + use);
        }
        if (module.getProvides() != null) {
            for (ServiceInfo si : module.getProvides()) lines.add("provides " + si.getInterfaceTypeName() + ' ' + Arrays.toString(si.getImplementationTypeNames()));
        }

        for (String line : lines) sb.append(line).append('\n');
        return sb.toString();
    }

    protected static void describe(TreeSet<String> lines, String keyword, PackageInfo[] packageInfos) {
        if (packageInfos != null) {
            for (PackageInfo pi : packageInfos) {
                String[] targets = pi.getModuleInfoNames();
                if (targets != null) {
                    targets = targets.clone();
                    Arrays.sort(targets);
                }
                lines.add(keyword + ' ' + pi.getInternalName() + ' ' + pi.getFlags() + ' ' + Arrays.toString(targets));
            }
        }
    }
}
