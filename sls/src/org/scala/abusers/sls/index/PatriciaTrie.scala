package org.scala.abusers.sls.index

class PatriciaTrie[V] private (private val root: PatriciaTrie.Node[V], val size: Int) {

  import PatriciaTrie.*

  def insert(key: String, value: V): PatriciaTrie[V] = {
    val (newRoot, replaced) = insertNode(root, key, 0, value)
    PatriciaTrie(newRoot, if replaced then size else size + 1)
  }

  def remove(key: String): PatriciaTrie[V] = {
    removeNode(root, key, 0) match {
      case Some(newRoot) => PatriciaTrie(newRoot, size - 1)
      case None          => this
    }
  }

  def get(key: String): Option[V] = getNode(root, key, 0)

  def prefixSearch(prefix: String): List[(String, V)] = {
    val buf = List.newBuilder[(String, V)]
    prefixSearchNode(root, prefix, 0, "", buf)
    buf.result()
  }

  def update(key: String, f: V => V): PatriciaTrie[V] =
    get(key) match {
      case Some(v) => insert(key, f(v))
      case None    => this
    }

  def isEmpty: Boolean = size == 0
}

object PatriciaTrie {

  def empty[V]: PatriciaTrie[V] = PatriciaTrie(Node(None, Map.empty), 0)

  private case class Node[V](
      value: Option[V],
      children: Map[Char, (String, Node[V])],
  )

  private def commonPrefixLength(a: String, aOff: Int, b: String, bOff: Int): Int = {
    var i = 0
    while (aOff + i < a.length && bOff + i < b.length && a.charAt(aOff + i) == b.charAt(bOff + i))
      i += 1
    i
  }

  private def insertNode[V](node: Node[V], key: String, offset: Int, value: V): (Node[V], Boolean) =
    if offset == key.length then
      val replaced = node.value.isDefined
      (node.copy(value = Some(value)), replaced)
    else {
      val c = key.charAt(offset)
      node.children.get(c) match {
        case None =>
          val label = key.substring(offset)
          val child = Node(Some(value), Map.empty)
          (node.copy(children = node.children + (c -> (label, child))), false)

        case Some((label, child)) =>
          val cpl = commonPrefixLength(label, 0, key, offset)
          if cpl == label.length then
            val (newChild, replaced) = insertNode(child, key, offset + cpl, value)
            (node.copy(children = node.children + (c -> (label, newChild))), replaced)
          else {
            // Split edge
            val splitLabel = label.substring(0, cpl)
            val oldSuffix = label.substring(cpl)
            val oldSuffixChar = oldSuffix.charAt(0)

            val splitNode =
              if offset + cpl == key.length then {
                // New key ends at split point
                val newChildren = Map(oldSuffixChar -> (oldSuffix, child))
                Node(Some(value), newChildren)
              } else {
                val newSuffix = key.substring(offset + cpl)
                val newSuffixChar = newSuffix.charAt(0)
                val newLeaf = Node[V](Some(value), Map.empty)
                val newChildren = Map(
                  oldSuffixChar -> (oldSuffix, child),
                  newSuffixChar -> (newSuffix, newLeaf),
                )
                Node(None, newChildren)
              }

            (node.copy(children = node.children + (c -> (splitLabel, splitNode))), false)
          }
      }
    }

  private def removeNode[V](node: Node[V], key: String, offset: Int): Option[Node[V]] =
    if offset == key.length then {
      if node.value.isEmpty then None // key not found
      else {
        val stripped = node.copy(value = None)
        Some(compress(stripped))
      }
    } else {
      val c = key.charAt(offset)
      node.children.get(c) match {
        case None => None
        case Some((label, child)) =>
          val cpl = commonPrefixLength(label, 0, key, offset)
          if cpl < label.length then None // key not found
          else
            removeNode(child, key, offset + cpl) match {
              case None => None // key not found deeper
              case Some(newChild) =>
                val newNode =
                  if newChild.value.isEmpty && newChild.children.isEmpty then
                    node.copy(children = node.children - c)
                  else
                    node.copy(children = node.children + (c -> (label, newChild)))
                Some(compress(newNode))
            }
      }
    }

  private def compress[V](node: Node[V]): Node[V] =
    if node.value.isEmpty && node.children.size == 1 then {
      val (_, (label, child)) = node.children.head
      if child.children.size <= 1 && child.value.isDefined then node
      else if child.value.isEmpty && child.children.size == 1 then {
        // Merge: node (no value, 1 child) -> child (no value, 1 child) => merge labels
        val (_, (childLabel, grandchild)) = child.children.head
        val merged = label + childLabel
        Node(None, Map(merged.charAt(0) -> (merged, grandchild)))
      } else node
    } else node

  private def getNode[V](node: Node[V], key: String, offset: Int): Option[V] =
    if offset == key.length then node.value
    else {
      val c = key.charAt(offset)
      node.children.get(c) match {
        case None => None
        case Some((label, child)) =>
          val cpl = commonPrefixLength(label, 0, key, offset)
          if cpl < label.length then None
          else getNode(child, key, offset + cpl)
        }
    }

  private def prefixSearchNode[V](
      node: Node[V],
      prefix: String,
      offset: Int,
      currentKey: String,
      buf: collection.mutable.Builder[(String, V), List[(String, V)]],
  ): Unit =
    if offset >= prefix.length then
      collectAll(node, currentKey, buf)
    else {
      val c = prefix.charAt(offset)
      node.children.get(c) match {
        case None => ()
        case Some((label, child)) =>
          val cpl = commonPrefixLength(label, 0, prefix, offset)
          if cpl == label.length then
            prefixSearchNode(child, prefix, offset + cpl, currentKey + label, buf)
          else if offset + cpl >= prefix.length then
            // prefix ends in the middle of this edge — all descendants match
            collectAll(child, currentKey + label, buf)
          // else: mismatch within edge, no results
      }
    }

  private def collectAll[V](
      node: Node[V],
      currentKey: String,
      buf: collection.mutable.Builder[(String, V), List[(String, V)]],
  ): Unit = {
    node.value.foreach(v => buf += ((currentKey, v)))
    node.children.values.foreach { case (label, child) =>
      collectAll(child, currentKey + label, buf)
    }
  }
}
