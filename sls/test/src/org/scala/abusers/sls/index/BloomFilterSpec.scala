package org.scala.abusers.sls.index

import cats.effect.IO
import weaver.*

object BloomFilterSpec extends SimpleIOSuite {

  test("added element is reported as maybe present") {
    val bf = BloomFilter(100, 0.01).add("hello")
    IO(expect(bf.mightContain("hello")))
  }

  test("never-added element is reported as absent") {
    val bf = BloomFilter(100, 0.01).add("hello")
    IO(expect(!bf.mightContain("world")))
  }

  test("empty filter returns false for all queries") {
    val bf = BloomFilter(100, 0.01)
    IO(
      expect(!bf.mightContain("anything")) &&
        expect(!bf.mightContain("")) &&
        expect(!bf.mightContain("test"))
    )
  }

  test("adding same element twice doesn't change behavior") {
    val bf1 = BloomFilter(100, 0.01).add("x")
    val bf2 = bf1.add("x")
    IO(
      expect(bf1.mightContain("x")) &&
        expect(bf2.mightContain("x"))
    )
  }

  test("empty string can be added and queried") {
    val bf = BloomFilter(100, 0.01).add("")
    IO(expect(bf.mightContain("")))
  }

  test("false positive rate within 2x of configured rate for 1000 elements") {
    val n      = 1000
    val fpRate = 0.01
    val bf     = (0 until n).foldLeft(BloomFilter(n, fpRate)) { (f, i) =>
      f.add(s"element-$i")
    }

    // Test with 10000 elements that were NOT added
    val testCount      = 10000
    val falsePositives = (0 until testCount).count { i =>
      bf.mightContain(s"other-$i")
    }
    val observedRate = falsePositives.toDouble / testCount

    IO(expect(observedRate < fpRate * 2))
  }

  test("zero false negatives for 1000 elements") {
    val n  = 1000
    val bf = (0 until n).foldLeft(BloomFilter(n, 0.01)) { (f, i) =>
      f.add(s"elem-$i")
    }
    val allFound = (0 until n).forall(i => bf.mightContain(s"elem-$i"))
    IO(expect(allFound))
  }

  test("union of two filters contains elements from both") {
    val bf1    = BloomFilter(100, 0.01).add("alpha").add("beta")
    val bf2    = BloomFilter(100, 0.01).add("gamma").add("delta")
    val merged = bf1.union(bf2)
    IO(
      expect(merged.mightContain("alpha")) &&
        expect(merged.mightContain("beta")) &&
        expect(merged.mightContain("gamma")) &&
        expect(merged.mightContain("delta"))
    )
  }
}
