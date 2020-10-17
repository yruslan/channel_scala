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

import org.scalatest.wordspec.AnyWordSpec

private class SimpleLinkListSuite extends AnyWordSpec {
  "SimpleLinkedList" should {
    "initialize as an empty list" in {
      val lst = new SimpleLinkedList[Int]

      assert(lst.isEmpty)
      assert(lst.size == 0)
    }
  }

  "append()" should {
    "add a single element to the list" in {
      val lst = new SimpleLinkedList[Int]

      lst.append(1)

      assert(!lst.isEmpty)
      assert(lst.size == 1)
      assert(lst.head == 1)
    }

    "add several elements to the list" in {
      val lst = new SimpleLinkedList[Int]

      lst.append(1)
      lst.append(2)
      lst.append(3)

      assert(!lst.isEmpty)
      assert(lst.size == 3)
      assert(lst.head == 1)
    }
  }

  "remove()" should {
    "remove a single element to the list" in {
      val lst = new SimpleLinkedList[Int]

      lst.append(1)
      lst.remove(1)

      assert(lst.isEmpty)
      assert(lst.size == 0)
    }

    "remove an element in the middle of the list" in {
      val lst = new SimpleLinkedList[Int]

      lst.append(1)
      lst.append(2)
      lst.append(3)

      lst.remove(1)

      assert(!lst.isEmpty)
      assert(lst.size == 2)
      assert(lst.head == 2)
    }

    "remove an element in the end of the list" in {
      val lst = new SimpleLinkedList[Int]

      lst.append(1)
      lst.append(2)
      lst.append(3)

      lst.remove(3)

      assert(!lst.isEmpty)
      assert(lst.size == 2)
      assert(lst.head == 1)
    }

    "remove two elements from the list" in {
      val lst = new SimpleLinkedList[Int]

      lst.append(1)
      lst.append(2)
      lst.append(3)

      lst.remove(3)
      assert(lst.head == 1)

      lst.remove(1)
      assert(lst.head == 2)

      assert(!lst.isEmpty)
      assert(lst.size == 1)
    }

    "remove 1ll elements from the list" in {
      val lst = new SimpleLinkedList[Int]

      lst.append(1)
      lst.append(2)
      lst.append(3)

      lst.remove(2)
      lst.remove(3)
      lst.remove(1)

      assert(lst.isEmpty)
      assert(lst.size == 0)
    }

    "not remove element that does not exist in the list" in {
      val lst = new SimpleLinkedList[Int]

      lst.append(1)
      lst.append(2)
      lst.append(3)

      lst.remove(4)

      assert(!lst.isEmpty)
      assert(lst.size == 3)
    }

    "throw an exception on empty list" in {
      val lst = new SimpleLinkedList[Int]

      intercept[NoSuchElementException] {
        lst.remove(1)
      }
    }
  }

  "clear()" should {
    "empty the list" in {
      val lst = new SimpleLinkedList[Int]

      lst.append(1)
      lst.append(2)
      lst.append(3)

      lst.clear()

      assert(lst.isEmpty)
      assert(lst.size == 0)
    }

  }

  "foreach()" should {
    "process nothing for an empty list" in {
      val lst = new SimpleLinkedList[Int]

      var i = 0
      lst.foreach(_ => i += 1)

      assert(i == 0)
    }

    "process all elements of the list" in {
      val lst = new SimpleLinkedList[Int]

      lst.append(1)
      lst.append(2)
      lst.append(3)

      var i = 0
      lst.foreach(a => i += a)

      assert(i == 6)
    }
  }

  "head()" should {
    "return head element if it is the only one in the list" in {
      val lst = new SimpleLinkedList[Int]

      lst.append(1)

      assert(lst.head == 1)
      assert(lst.head == 1)

      assert(!lst.isEmpty)
      assert(lst.size == 1)
    }
    "return head element of the list" in {
      val lst = new SimpleLinkedList[Int]

      lst.append(1)
      lst.append(2)
      lst.append(3)

      assert(lst.head == 1)
      assert(lst.head == 1)
      assert(lst.head == 1)

      assert(!lst.isEmpty)
      assert(lst.size == 3)
    }

    "throw an exception on empty list" in {
      val lst = new SimpleLinkedList[Int]

      intercept[NoSuchElementException] {
        lst.head
      }
    }
  }
  "returnHeadAndRotate()" should {
    "return head element if it is the only one in the list" in {
      val lst = new SimpleLinkedList[Int]

      lst.append(1)

      assert(lst.returnHeadAndRotate() == 1)
      assert(lst.returnHeadAndRotate() == 1)
      assert(lst.returnHeadAndRotate() == 1)

      assert(!lst.isEmpty)
      assert(lst.size == 1)
    }
    "return head element and rotate the rest of the list" in {
      val lst = new SimpleLinkedList[Int]

      lst.append(1)
      lst.append(2)
      lst.append(3)

      // "The definition of insanity is doing the same thing over and over again, but expecting different results."
      //                                                                                    --  Albert Einstein
      assert(lst.returnHeadAndRotate() == 1)
      assert(lst.returnHeadAndRotate() == 2)
      assert(lst.returnHeadAndRotate() == 3)
      assert(lst.returnHeadAndRotate() == 1)
      assert(lst.returnHeadAndRotate() == 2)
      assert(lst.returnHeadAndRotate() == 3)

      assert(!lst.isEmpty)
      assert(lst.size == 3)
    }
  }

  "throw an exception on empty list" in {
    val lst = new SimpleLinkedList[Int]

    intercept[NoSuchElementException] {
      lst.returnHeadAndRotate()
    }
  }

}
