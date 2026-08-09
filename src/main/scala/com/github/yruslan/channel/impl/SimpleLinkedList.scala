/*
 * Copyright (c) 2020 Ruslan Yushchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.yruslan.channel.impl

/**
 * The motivation for implementing this tiny linked list is to have very fast append and remove operations
 * when the number of elements is small.
 */
class SimpleLinkedList[T] {
  class Elem[U](var el: U, var next: Elem[U])

  private var first: Elem[T] = _
  private var last: Elem[T] = _
  private var count = 0

  def append(a: T): Unit = this.synchronized {
    val newElement = new Elem[T](a, null)
    if (first == null) {
      first = newElement
      last = first
    } else {
       last.next = newElement
       last = newElement
    }
    count += 1
  }

  def isEmpty: Boolean = {
    first == null
  }

  def nonEmpty: Boolean = {
    first != null
  }

  def size: Int = count

  def head: T = {
    if (first == null) {
      throw new NoSuchElementException
    } else {
      first.el
    }
  }

  def returnHeadAndRotate(): T = this.synchronized {
    if (first == null) {
      throw new NoSuchElementException
    } else {
      val ret = first.el
      rotate()
      ret
    }
  }

  def remove(a: T): Unit = this.synchronized {
    if (first == null) {
      return
    }

    if (first.el == a) {
      dropFirst()
    } else {
      var removed = false
      var p = first
      while (p.next != null && !removed) {
        if (p.next.el == a) {
          p.next = p.next.next
          if (p.next == null) {
            last = p
          }
          removed = true
          count -= 1
        } else {
          p = p.next
        }
      }
    }
  }

  def containsNot(a: T): Boolean = this.synchronized {
    var p = first
    while (p != null) {
      if (p.el != a) {
        return true
      }
      p = p.next
    }
    false
  }

  def findNot(a: T): Option[T] = this.synchronized {
    var p = first
    while (p != null) {
      if (p.el != a) {
        return Option(p.el)
      }
      p = p.next
    }
    None
  }

  def clear(): Unit = this.synchronized {
    first = null
    last = null
    count = 0
  }

  def foreach[U](f: T => U): Unit = this.synchronized {
    var p = first
    while (p != null) {
      f(p.el)
      p = p.next
    }
  }

  private def dropFirst(): Unit = {
    if (first == last) {
      clear()
    } else {
      first = first.next
      count -= 1
    }
  }

  private def rotate(): Unit = {
    if (first != last) {
      val tmp = first
      first = tmp.next
      last.next = tmp
      last = tmp
      tmp.next = null
    }
  }
}
