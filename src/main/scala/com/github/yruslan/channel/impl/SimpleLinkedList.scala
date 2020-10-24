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

  def clear(): Unit = this.synchronized {
    first = null
    last = null
    count = 0
  }

  def foreach(f: T => Unit): Unit = this.synchronized {
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
