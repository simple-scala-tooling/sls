package org.scala.abusers.sls.index

class BloomFilter private (
    private val bits: Array[Long],
    private val numHashFunctions: Int,
    private val numBits: Int,
) {

  def add(element: String): BloomFilter = {
    val newBits = bits.clone()
    val (h1, h2) = hashPair(element)
    var i = 0
    while (i < numHashFunctions) {
      val idx = bitIndex(h1, h2, i)
      newBits(idx >> 6) |= 1L << (idx & 63)
      i += 1
    }
    new BloomFilter(newBits, numHashFunctions, numBits)
  }

  def mightContain(element: String): Boolean = {
    val (h1, h2) = hashPair(element)
    var i = 0
    while (i < numHashFunctions) {
      val idx = bitIndex(h1, h2, i)
      if ((bits(idx >> 6) & (1L << (idx & 63))) == 0) return false
      i += 1
    }
    true
  }

  def union(other: BloomFilter): BloomFilter = {
    require(numBits == other.numBits && numHashFunctions == other.numHashFunctions)
    val newBits = new Array[Long](bits.length)
    var i = 0
    while (i < bits.length) {
      newBits(i) = bits(i) | other.bits(i)
      i += 1
    }
    new BloomFilter(newBits, numHashFunctions, numBits)
  }

  private def bitIndex(h1: Int, h2: Int, i: Int): Int = {
    val combined = h1 + i * h2
    (combined & Int.MaxValue) % numBits
  }

  private def hashPair(element: String): (Int, Int) = {
    val h1 = scala.util.hashing.MurmurHash3.stringHash(element, 0x9747b28c)
    val h2 = scala.util.hashing.MurmurHash3.stringHash(element, 0xe6546b64)
    (h1, h2)
  }
}

object BloomFilter {

  def apply(expectedElements: Int, falsePositiveRate: Double): BloomFilter = {
    val m = optimalNumBits(expectedElements, falsePositiveRate)
    val k = optimalNumHash(m, expectedElements)
    val arraySize = ((m - 1) >> 6) + 1
    new BloomFilter(new Array[Long](arraySize), k, m)
  }

  private def optimalNumBits(n: Int, p: Double): Int = {
    val m = (-n * math.log(p) / (math.log(2) * math.log(2))).toInt
    math.max(m, 64)
  }

  private def optimalNumHash(m: Int, n: Int): Int = {
    val k = (m.toDouble / n * math.log(2)).toInt
    math.max(k, 1)
  }
}
