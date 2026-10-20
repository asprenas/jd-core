package org.jd.core.test.java8;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * Java 8 (JEP 104, JEP 120): type annotations and repeatable annotations.
 */
public class AnnotationsJava8 {
    @Retention(RetentionPolicy.RUNTIME)
    @Repeatable(Tags.class)
    @interface Tag {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Tags {
        Tag[] value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
    @interface NonNull {
    }

    @Tag("a")
    @Tag("b")
    public void repeated() {
    }

    @Tag("single")
    public void single() {
    }

    public @NonNull String typeUse(@NonNull String param, List<@NonNull String> list) throws @NonNull RuntimeException {
        @NonNull String local = param;
        Object o = (@NonNull Object) local;
        return list.isEmpty() ? local : (@NonNull String) o;
    }

    public <@NonNull T> T typeParameter(T value) {
        return value;
    }
}
