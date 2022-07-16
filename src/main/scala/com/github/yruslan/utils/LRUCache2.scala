/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 Ruslan Yushchenko
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * For more information, please refer to <http://opensource.org/licenses/MIT>
 */

package com.github.yruslan.utils

import scala.collection.mutable

/** A simplistic implementation of LRU cache. */
class LRUCache2[K, V](maxSize: Int) {
  class Node(val key: K, var value: V, var next: Node, var prev: Node)

  private var head: Node = _
  private var tail: Node = _
  private var size = 0
  private val cache = mutable.HashMap.empty[K, Node]

  def apply(key: K): V = {
    get(key) match {
      case Some(value) =>
        value
      case None =>
        throw new NoSuchElementException(s"No value found for key: $key")
    }
  }

  def get(key: K): Option[V] = synchronized {
    cache.get(key).map { node =>
      putToTop(node)
      node.value
    }
  }

  def put(key: K, value: V): Unit = synchronized {
    val nodeOpt = cache.get(key)

    nodeOpt match {
      case Some(n) =>
        n.value = value
        putToTop(n)
      case None =>
        size += 1
        val node = new Node(key, value, head, null)
        if (head != null) {
          head.prev = node
        }
        head = node
        cache.put(key, node)
        if (size > maxSize) {
          cache.remove(tail.key)
          val last = tail.prev
          last.next = null
          tail = last
          size -= 1
        }
        if (tail == null) {
          tail = node
        }
    }
  }

  def remove(key: K): Unit = synchronized {
    val nodeOpt = cache.get(key)

    nodeOpt.foreach(node => {
      removeFromList(node)
      cache.remove(key)
      size -= 1
    })
  }

  private def removeFromList(node: Node): Unit = {
    if (node.prev != null) {
      node.prev.next = node.next
    } else {
      head = node.next
    }
    if (node.next != null) {
      node.next.prev = node.prev
    } else {
      tail = node.prev
    }
  }

  private def putToTop(node: Node): Unit = {
    if (node != head) {
      if (tail == node) {
        tail = node.prev
      }
      if (node.next != null) {
        node.next.prev = node.prev
      }
      if (node.prev != null) {
        node.prev.next = node.next
      }
      node.next = head
      node.prev = null
      if (head != null) {
        head.prev = node
      }
      head = node
    }
  }
}
