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

import org.scalatest.wordspec.AnyWordSpec

class LRUCacheSuite extends AnyWordSpec {
  "LRUCache" should {
    "remember items put there" in {
      val cache = new LRUCache[Int, String](3)
      cache.put(1, "one")
      cache.put(2, "two")
      cache.put(3, "three")
      assert(cache.get(1).contains("one"))
      assert(cache.get(2).contains("two"))
      assert(cache.get(3).contains("three"))
    }

    "forget old items" in {
      val cache = new LRUCache[Int, String](3)
      cache.put(1, "one")
      cache.put(2, "two")
      cache.put(3, "three")
      cache.put(4, "four")
      assert(cache.get(1).isEmpty)
      assert(cache.get(2).contains("two"))
      assert(cache.get(3).contains("three"))
      assert(cache.get(4).contains("four"))
    }

    "remember frequently used items" in {
      val cache = new LRUCache[Int, String](3)
      cache.put(1, "one")
      cache.put(2, "two")
      cache.put(3, "three")
      cache.get(1)
      cache.get(3)
      cache.put(4, "four")

      assert(cache.get(1).contains("one"))
      assert(cache.get(2).isEmpty)
      assert(cache.get(3).contains("three"))
      assert(cache.get(4).contains("four"))
    }

    "allow invalidating of values" in {
      val cache = new LRUCache[Int, String](3)
      cache.put(1, "one")
      cache.put(2, "two")
      cache.put(3, "three")
      cache.remove(3)
      cache.remove(4)
      cache.get(1)
      cache.get(3)
      cache.put(4, "four")

      assert(cache(1) == "one")
      assert(cache(2) == "two")
      assert(cache(3) == null)
      assert(cache(4) == "four")
    }

    "have the expected performance" in {
      val cache = new LRUCache[Int, String](1000)
      val n = 1000000
      val start = System.currentTimeMillis()
      for (i <- 1 to n) {
        cache.put(i, i.toString)
      }
      val end = System.currentTimeMillis()

      assert(end - start < 1000)

      val start2 = System.currentTimeMillis()
      for (i <- 1 to n) {
        cache.get(i)
      }
      val end2 = System.currentTimeMillis()
      assert(end2 - start2 < 1000)
    }
  }

}