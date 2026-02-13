# Index Implementation Progress

## Completed
- **0.1** IndexTypes — core data model types
- **0.2** PatriciaTrie — immutable compressed radix tree with prefix search
- **0.3** BloomFilter — per-JAR pre-filtering with double hashing
- **0.4** CamelCaseUtils — CamelCase skeleton extraction for workspace symbol search

- **1.1** ProjectIndex — mutable project index with file-level invalidation
- **1.2** DependencyIndex — immutable dependency index with per-JAR Bloom filters
- **1.3** SymbolIndex — unified facade over both index tiers

- **2.1** TastyIndexer — TASTy Inspector-based indexer with tree walking

## Next
- **2.2** BytecodeIndexer — ASM-based bytecode indexer for Scala 2/Java JARs
