package crossproducer

/** Fixture used by CrossProducerSpec to verify that TastyIndexer, BytecodeIndexer,
  * and JavaIndexer (via LibJ) emit consistent SymbolIds for the same source-level shapes.
  * Shape: top-level class, companion object, overloaded methods, inner class.
  */
class Lib {
  def compute(x: Int): Int        = x * 2
  def compute(x: String): String  = x.toUpperCase
  class Inner {
    def value: Int = 42
  }
}

object Lib {
  def create(): Lib = new Lib()
}
