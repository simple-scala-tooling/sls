package crossproducer;

/**
 * Java mirror of {@link Lib} for cross-producer SymbolId comparison in CrossProducerSpec.
 * Overloaded methods are the key case: bytecode uses {@code #compute} while TASTy/Java
 * indexers use {@code .compute}, revealing the Phase-1 canonicalization bug.
 */
public class LibJ {
    public int compute(int x) { return x * 2; }
    public String compute(String x) { return x.toUpperCase(); }

    public static class Inner {
        public int value() { return 42; }
    }

    public static LibJ create() { return new LibJ(); }
}
