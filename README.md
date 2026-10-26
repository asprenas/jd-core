# JD-Core

JD-Core is a JAVA decompiler written in JAVA.

> **This repository is a fork of [java-decompiler/jd-core](https://github.com/java-decompiler/jd-core).**
> It is based on upstream commit `fafc656` and adds build/test maintenance
> (Gradle 9.1, JDK 17 / 21 / 25 support, JUnit 5, GitHub Actions).
> See [Releases](https://github.com/asprenas/jd-core/releases) for changes.

### Original project
- Java Decompiler project home page:
[https://java-decompiler.github.io](https://java-decompiler.github.io)
- JD-Core source code (upstream):
[https://github.com/java-decompiler/jd-core](https://github.com/java-decompiler/jd-core)
- Upstream releases:
[https://github.com/java-decompiler/jd-core/releases](https://github.com/java-decompiler/jd-core/releases)

## Description
JD-Core is a standalone JAVA library containing the JAVA decompiler of
"Java Decompiler project". It supports Lambda expressions, method references
and default methods. JD-Core is the engine of JD-GUI.

The library requires Java 8 or later at runtime (bytecode is compiled with `--release 8`).

Known decompilation issues found with recent libraries (e.g. nested capturing lambdas,
missing casts on overloaded calls) are listed as TODO in
[JarFileToJavaSourceTest](src/test/java/org/jd/core/v1/JarFileToJavaSourceTest.java).

## How to build JD-Core ?
Requires JDK 17 or later (Gradle 9.1). Tested on JDK 17, 21 and 25.
The generated jar stays Java 8 compatible.

```
> git clone https://github.com/asprenas/jd-core.git
> cd jd-core
> ./gradlew build
```
On Windows, use `gradlew.bat build`.

generate _"build/libs/jd-core-x.y.z.jar"_

## How to use JD-Core ?

1. Implement the
_[org.jd.core.v1.api.loader.Loader](https://github.com/asprenas/jd-core/blob/master/src/main/java/org/jd/core/v1/api/loader/Loader.java)_
interface,
2. Implement the
_[org.jd.core.v1.api.printer.Printer](https://github.com/asprenas/jd-core/blob/master/src/main/java/org/jd/core/v1/api/printer/Printer.java)_
interface,
3. And call the method _"decompile(loader, printer, internalTypeName);"_

## Example

1. Implement the _Loader_ interface:
```java
Loader loader = new Loader() {
    @Override
    public byte[] load(String internalName) throws LoaderException {
        InputStream is = this.getClass().getResourceAsStream("/" + internalName + ".class");

        if (is == null) {
            return null;
        } else {
            try (InputStream in=is; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int read = in.read(buffer);

                while (read > 0) {
                    out.write(buffer, 0, read);
                    read = in.read(buffer);
                }

                return out.toByteArray();
            } catch (IOException e) {
                throw new LoaderException(e);
            }
        }
    }

    @Override
    public boolean canLoad(String internalName) {
        return this.getClass().getResource("/" + internalName + ".class") != null;
    }
};
```

2. Implement the _Printer_ interface
```java
Printer printer = new Printer() {
    protected static final String TAB = "  ";
    protected static final String NEWLINE = "\n";

    protected int indentationCount = 0;
    protected StringBuilder sb = new StringBuilder();

    @Override public String toString() { return sb.toString(); }

    @Override public void start(int maxLineNumber, int majorVersion, int minorVersion) {}
    @Override public void end() {}

    @Override public void printText(String text) { sb.append(text); }
    @Override public void printNumericConstant(String constant) { sb.append(constant); }
    @Override public void printStringConstant(String constant, String ownerInternalName) { sb.append(constant); }
    @Override public void printKeyword(String keyword) { sb.append(keyword); }
    @Override public void printDeclaration(int type, String internalTypeName, String name, String descriptor) { sb.append(name); }
    @Override public void printReference(int type, String internalTypeName, String name, String descriptor, String ownerInternalName) { sb.append(name); }

    @Override public void indent() { this.indentationCount++; }
    @Override public void unindent() { this.indentationCount--; }

    @Override public void startLine(int lineNumber) { for (int i=0; i<indentationCount; i++) sb.append(TAB); }
    @Override public void endLine() { sb.append(NEWLINE); }
    @Override public void extraLine(int count) { while (count-- > 0) sb.append(NEWLINE); }

    @Override public void startMarker(int type) {}
    @Override public void endMarker(int type) {}
};
```

3. And call the method _"decompile(loader, printer, internalTypeName);"_
```java
ClassFileToJavaSourceDecompiler decompiler = new ClassFileToJavaSourceDecompiler();

decompiler.decompile(loader, printer, "path/to/YourClass");

String source = printer.toString();
```

## Multi-release jar

For a multi-release jar (JEP 238), wrap the loader of the jar entries in a
_[MultiReleaseLoader](https://github.com/asprenas/jd-core/blob/master/src/main/java/org/jd/core/v1/api/loader/MultiReleaseLoader.java)_:
each class is read from the highest `META-INF/versions/k/` directory with `k` lower than or equal to the target
version, or from the root, as the JVM does. Checking the manifest is the responsibility of the caller:
```java
JarFile jarFile = new JarFile(file);
Loader loader = ...; // reads the entries of 'jarFile', names relative to the root of the jar

if (jarFile.isMultiRelease()) { // Java 9+, or 'Multi-Release: true' in the manifest
    loader = new MultiReleaseLoader(loader, 17); // decompile the classes run by Java 17
}

decompiler.decompile(loader, printer, "path/to/YourClass");
```

## License
Released under the [GNU GPL v3](LICENSE).
