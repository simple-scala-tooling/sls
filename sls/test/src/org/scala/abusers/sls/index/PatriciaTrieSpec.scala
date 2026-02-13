package org.scala.abusers.sls.index

import cats.effect.IO
import weaver.*

object PatriciaTrieSpec extends SimpleIOSuite {

  test("insert and retrieve a single key-value pair") {
    val trie = PatriciaTrie.empty[Int].insert("hello", 42)
    IO(expect(trie.get("hello") == Some(42)))
  }

  test("insert multiple keys with shared prefix") {
    val trie = PatriciaTrie.empty[Int]
      .insert("abc", 1)
      .insert("abd", 2)
      .insert("xyz", 3)
    IO(
      expect(trie.get("abc") == Some(1)) &&
        expect(trie.get("abd") == Some(2)) &&
        expect(trie.get("xyz") == Some(3))
    )
  }

  test("prefix search returns matching entries only") {
    val trie = PatriciaTrie.empty[Int]
      .insert("abc", 1)
      .insert("abd", 2)
      .insert("xyz", 3)
    val results = trie.prefixSearch("ab").map(_._1).toSet
    IO(
      expect(results == Set("abc", "abd"))
    )
  }

  test("prefix search with empty string returns all entries") {
    val trie = PatriciaTrie.empty[Int]
      .insert("a", 1)
      .insert("b", 2)
    val results = trie.prefixSearch("")
    IO(expect(results.size == 2))
  }

  test("prefix search for non-existent prefix returns empty") {
    val trie = PatriciaTrie.empty[Int].insert("abc", 1)
    IO(expect(trie.prefixSearch("z").isEmpty))
  }

  test("remove existing key; siblings remain") {
    val trie = PatriciaTrie.empty[Int]
      .insert("abc", 1)
      .insert("abd", 2)
      .remove("abc")
    IO(
      expect(trie.get("abc").isEmpty) &&
        expect(trie.get("abd") == Some(2))
    )
  }

  test("remove non-existent key returns unchanged trie") {
    val trie = PatriciaTrie.empty[Int].insert("abc", 1)
    val trie2 = trie.remove("xyz")
    IO(
      expect(trie2.get("abc") == Some(1)) &&
        expect(trie2.size == 1)
    )
  }

  test("insert same key twice replaces value") {
    val trie = PatriciaTrie.empty[Int]
      .insert("abc", 1)
      .insert("abc", 99)
    IO(
      expect(trie.get("abc") == Some(99)) &&
        expect(trie.size == 1)
    )
  }

  test("empty string key") {
    val trie = PatriciaTrie.empty[Int].insert("", 42)
    IO(
      expect(trie.get("") == Some(42)) &&
        expect(trie.size == 1)
    )
  }

  test("keys that are prefixes of each other") {
    val trie = PatriciaTrie.empty[Int]
      .insert("a", 1)
      .insert("ab", 2)
      .insert("abc", 3)
    IO(
      expect(trie.get("a") == Some(1)) &&
        expect(trie.get("ab") == Some(2)) &&
        expect(trie.get("abc") == Some(3)) &&
        expect(trie.size == 3)
    )
  }

  test("size tracks correctly through insert and remove") {
    val trie = PatriciaTrie.empty[Int]
      .insert("a", 1)
      .insert("b", 2)
      .insert("c", 3)
      .remove("b")
    IO(expect(trie.size == 2))
  }

  test("insert + remove of same single key yields empty trie") {
    val trie = PatriciaTrie.empty[Int]
      .insert("hello", 1)
      .remove("hello")
    IO(
      expect(trie.isEmpty) &&
        expect(trie.size == 0)
    )
  }

  test("large-scale: insert 10000 entries and prefix search") {
    val entries = (0 until 10000).map(i => f"key$i%05d" -> i)
    val trie = entries.foldLeft(PatriciaTrie.empty[Int]) { case (t, (k, v)) => t.insert(k, v) }
    val results = trie.prefixSearch("key001") // should get key00100..key00199
    IO(
      expect(trie.size == 10000) &&
        expect(results.size == 100)
    )
  }

  test("update modifies existing value") {
    val trie = PatriciaTrie.empty[Int]
      .insert("x", 10)
      .update("x", _ + 5)
    IO(expect(trie.get("x") == Some(15)))
  }

  test("update on non-existent key is no-op") {
    val trie = PatriciaTrie.empty[Int].update("missing", _ + 1)
    IO(expect(trie.isEmpty))
  }

  test("get on non-existent key returns None") {
    IO(expect(PatriciaTrie.empty[Int].get("nope").isEmpty))
  }

  test("prefix search where prefix ends mid-edge") {
    val trie = PatriciaTrie.empty[Int]
      .insert("abcdef", 1)
      .insert("abcxyz", 2)
    val results = trie.prefixSearch("abcd").map(_._1)
    IO(expect(results == List("abcdef")))
  }
}
