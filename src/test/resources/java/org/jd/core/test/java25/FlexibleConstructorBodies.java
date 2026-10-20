package org.jd.core.test.java25;

/**
 * Java 25 (JEP 513): flexible constructor bodies. Statements may appear before
 * super(...) or this(...) as long as they do not use the instance under construction.
 * In bytecode these statements come before invokespecial <init> on uninitializedThis.
 */
public class FlexibleConstructorBodies {
    static class Base {
        protected final String name;

        Base(String name) {
            this.name = name;
        }
    }

    static class Validated extends Base {
        private final int length;

        Validated(String name) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("empty");
            }
            String trimmed = name.trim();
            super(trimmed);
            this.length = trimmed.length();
        }
    }

    static class PreInit extends Base {
        private final int size;

        PreInit(int size) {
            int clamped = Math.max(size, 0);
            super("size-" + clamped);
            this.size = clamped;
        }
    }

    static class FieldBeforeSuper extends Base {
        private final String tag;

        FieldBeforeSuper(String name) {
            this.tag = name.toUpperCase();
            super(name);
        }
    }

    static class ThisCall {
        private final int a;
        private final int b;

        ThisCall(int a) {
            int doubled = a * 2;
            this(a, doubled);
        }

        ThisCall(int a, int b) {
            this.a = a;
            this.b = b;
        }
    }

    static class LoopBeforeSuper extends Base {
        LoopBeforeSuper(String[] parts) {
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (!part.isEmpty()) {
                    sb.append(part).append('/');
                }
            }
            super(sb.toString());
        }
    }

    static class TryBeforeSuper extends Base {
        TryBeforeSuper(String s) {
            int n;
            try {
                n = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                n = -1;
            }
            super("n=" + n);
        }
    }

    static class SwitchBeforeSuper extends Base {
        SwitchBeforeSuper(int kind) {
            String label = switch (kind) {
                case 0 -> "zero";
                case 1 -> "one";
                default -> "many";
            };
            super(label);
        }
    }
}
