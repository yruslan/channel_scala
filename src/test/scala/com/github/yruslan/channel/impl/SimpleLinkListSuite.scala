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

    "do nothing on empty list" in {
      val lst = new SimpleLinkedList[Int]

      lst.remove(1)

      assert(lst.isEmpty)
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
