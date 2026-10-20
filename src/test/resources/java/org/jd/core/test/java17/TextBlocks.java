package org.jd.core.test.java17;

/**
 * Java 15 (JEP 378): text blocks. The class file only holds the resulting string
 * constants, the decompiler has to emit them with correct escaping.
 */
public class TextBlocks {
    public String html() {
        return """
            <html>
                <body>
                    <p>Hello, "world"</p>
                </body>
            </html>
            """;
    }

    public String noTrailingNewline() {
        return """
            line1
            line2""";
    }

    public String escapes() {
        return """
            tab\there
            quote \""" inside
            backslash \\ done
            unicode \u00e9
            """;
    }

    public String lineContinuation() {
        return """
            one \
            two \
            three
            """;
    }

    public String trailingSpaces() {
        return """
            keep\s\s
            trimmed   
            end
            """;
    }

    public String formatted(String name) {
        return """
            Hello, %s!
            """.formatted(name);
    }

    public String concatenated(String x) {
        return """
            a
            """ + x + """
            b
            """;
    }

    public String json(int id) {
        return """
            {
              "id": %d,
              "tags": ["x", "y"]
            }
            """.formatted(id);
    }
}
