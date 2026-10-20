/*
 * Java 25 (JEP 512): compact source files and instance main methods.
 * No package and no class declaration: the compiler wraps everything in a final
 * top-level class named after the file, so the class file is InstanceMain.class in
 * the unnamed package (zip root).
 */

String greeting = "hello";

void main() {
    IO.println(greeting);
    IO.println(helper(21));
}

int helper(int n) {
    return n * 2;
}
