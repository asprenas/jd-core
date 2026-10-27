package org.jd.core.v1;

import org.jd.core.v1.loader.ClassPathLoader;
import org.jd.core.v1.printer.PlainTextPrinter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Casts and local variables required by the recompilation of guava 12.0 (old javac, no local variable table) */
public class JavaGuavaRecompilationTest extends Java9InterfaceTest {
    protected String decompile(String internalTypeName) throws Exception {
        return decompile(new ClassPathLoader(), new PlainTextPrinter(), internalTypeName);
    }

    @Test
    public void uncheckedExceptionThrownWithoutCast() throws Exception {
        // '<X extends Exception> ... throws X': 'throw new AssertionError()', not '(X)new AssertionError()'
        String source = decompile("com/google/common/util/concurrent/Futures");

        assertTrue(source.contains("throw new AssertionError();"), "no cast of an error");
        assertTrue(source.contains("throw new ExecutionError((Error)cause);"), "no cast of an error");
        // '<V> getUninterruptibly(Future<V>)' with 'ListenableFuture<? extends I> inputFuture'
        assertTrue(source.contains("Uninterruptibles.getUninterruptibly(this.inputFuture);"), "no raw cast of the argument");
        // 'Arrays.asList(Constructor<?>[])' assigned to 'List<Constructor<X>>'
        assertTrue(source.contains("List<Constructor<X>> constructors = (List)Arrays.asList(exceptionClass.getConstructors());"), "raw cast");
    }

    @Test
    public void slotWithSameRawTypeIsOneVariable() throws Exception {
        // 'Comparator<? super E> comparator' and 'Comparator<Comparable> NATURAL_ORDER' in the same slot
        String source = decompile("com/google/common/collect/ImmutableSortedSet");

        assertTrue(source.contains("comparator = (Comparator)NATURAL_ORDER;"), "one variable with a raw cast");
        assertTrue(source.contains("return copyOfInternal(comparator, sortedSet);"), "one variable");
    }

    @Test
    public void methodTypeVariableOutOfStaticScope() throws Exception {
        // 'cast(key, value)' with '<B, T extends B> T cast(Class<T>, B)' in a static field initializer
        String source = decompile("com/google/common/collect/MutableClassToInstanceMap");

        assertTrue(source.contains("MutableClassToInstanceMap.cast((Class)key, value);"), "no cast to 'B' in a static context");
    }

    @Test
    public void rawCastOfReceiverRemoved() throws Exception {
        String source = decompile("com/google/common/collect/TreeMultiset");

        assertTrue(source.contains("range.contains(next.getTip().getKey())"), "no raw cast of the receiver");
    }

    @Test
    public void outerInstanceTypeArguments() throws Exception {
        String source = decompile("com/google/common/collect/StandardTable");

        assertTrue(source.contains("StandardTable.this.column((C)key)"), "parameter typed by the type variable of the outer instance");
    }

    @Test
    public void fieldOfAnotherInstanceOfTheCurrentType() throws Exception {
        // 'AbstractBiMap<V, K> inverse' in 'AbstractBiMap<K, V>': 'inverse.delegate' is a 'Map<V, K>'
        String source = decompile("com/google/common/collect/AbstractBiMap");

        assertTrue(source.contains("this.inverse.delegate.put(newValue, key);"), "no cast");
    }

    @Test
    public void wildcardSuperAcceptsSuperType() throws Exception {
        // 'CacheLoader<Object, V>' for 'CacheLoader<? super K, V>'
        String source = decompile("com/google/common/cache/LocalCache");

        assertTrue(source.contains("return this.localCache.get(key, new CacheLoader<Object, V>() {"), "no raw cast of the anonymous class");
    }

    @Test
    public void rawCastInAssignment() throws Exception {
        // '<C extends Comparable> Ordering<C> natural()' assigned to 'Comparator<? super K>'
        String source = decompile("com/google/common/collect/Maps");

        assertTrue(source.contains("forwardCmp = (Comparator)Ordering.natural();"), "raw cast");
    }
}
