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

package com.github.yruslan.channel

import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.Executors
import scala.concurrent._
import scala.concurrent.duration.{Duration, MILLISECONDS}

// This import is required for Scala 2.13 since it has a builtin Channel object.

class ChannelFilterSuite extends AnyWordSpec {
  implicit private val ec: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))

  "Channel.filter()" should {
    "filter input channel on recv()" in {
      val ch1 = Channel.make[Int](3)

      val ch2 = ch1.filter(v => v != 2)

      ch1.send(1)
      ch1.send(2)
      ch1.send(3)
      ch1.close()

      val v1 = ch2.recv()
      val v2 = ch2.recv()

      assert(v1 == 1)
      assert(v2 == 3)
    }

    "filter input channel on tryRecv()" when {
      "values either available or not" in {
        val ch1 = Channel.make[Int](3)

        val ch2 = ch1.filter(v => v != 2)

        val v1 = ch2.tryRecv()

        ch1.send(1)
        ch1.send(2)
        ch1.send(3)
        ch1.close()

        val v2 = ch2.tryRecv()
        val v3 = ch2.tryRecv()

        assert(v1.isEmpty)
        assert(v2.contains(1))
        assert(v3.contains(3))
      }
      "values available, but don't match" in {
        val ch1 = Channel.make[Int](3)

        val ch2 = ch1.filter(v => v == 3)

        ch1.send(1)
        ch1.send(2)

        val v1 = ch2.tryRecv()
        ch1.close()

        assert(v1.isEmpty)
      }
      "values available, but after non-matching ones" in {
        val ch1 = Channel.make[Int](3)

        val ch2 = ch1.filter(v => v == 3)

        ch1.send(1)
        ch1.send(2)
        ch1.send(3)

        val v1 = ch2.tryRecv()
        ch1.close()

        assert(v1.contains(3))
      }
    }

    "filter input channel on tryRecv(duration)" when {
      val timeout = Duration(2, MILLISECONDS)
      "values either available or not" in {
        val ch1 = Channel.make[Int](3)

        val ch2 = ch1.filter(v => v != 2)

        val v1 = ch2.tryRecv(timeout)

        ch1.send(1)
        ch1.send(2)
        ch1.send(3)
        ch1.close()

        val v2 = ch2.tryRecv(timeout)
        val v3 = ch2.tryRecv(timeout)

        assert(v1.isEmpty)
        assert(v2.contains(1))
        assert(v3.contains(3))
      }
      "values available, but don't match" in {
        val ch1 = Channel.make[Int](3)

        val ch2 = ch1.filter(v => v == 3)

        ch1.send(1)
        ch1.send(2)

        val v1 = ch2.tryRecv(timeout)
        ch1.close()

        assert(v1.isEmpty)
      }
      "values available, but after non-matching ones" in {
        val ch1 = Channel.make[Int](3)

        val ch2 = ch1.filter(v => v == 3)

        ch1.send(1)
        ch1.send(2)
        ch1.send(3)

        val v1 = ch2.tryRecv(timeout)
        ch1.close()

        assert(v1.contains(3))
      }
    }

    "filter input channel on recver()" in {
      val ch1 = Channel.make[Int](3)

      val ch2 = ch1.filter(v => v != 2)

      ch1.send(1)
      ch1.send(2)
      ch1.send(3)
      ch1.close()

      var v1 = 0
      Channel.select(ch2.recver { v => v1 = v })
      Channel.select(ch2.recver { v => v1 = v })

      var v2 = 0
      Channel.select(ch2.recver { v => v2 = v })

      assert(v1 == 1)
      assert(v2 == 3)
    }

    "filter input channel on fornew()" in {
      val ch1 = Channel.make[Int](2)

      val ch2 = ch1.filter(v => v != 2)

      ch1.send(1)
      ch1.send(2)

      var v1 = 0

      ch2.fornew(v => v1 = v)

      assert(v1 == 1)
    }

    "filter input channel on foreach()" in {
      val ch1 = Channel.make[Int](3)

      val ch2 = ch1.filter(v => v != 3)

      ch1.send(1)
      ch1.send(2)
      ch1.send(3)
      ch1.close()

      var v1 = 0

      ch2.foreach(v => v1 = v)

      assert(v1 == 2)
    }

    "filter input channel on filter(filter())" in {
      val ch1 = Channel.make[Int](5)

      val ch2 = ch1.filter(v => v != 3)
      val ch3 = ch2.filter(v => v != 4)

      ch1.send(1)
      ch1.send(3)
      ch1.send(4)
      ch1.send(2)
      ch1.send(3)
      ch1.close()

      val v1 = ch3.recv()
      val v2 = ch3.recv()

      assert(v1 == 1)
      assert(v2 == 2)
    }

    "filter input channel on filter(map())" in {
      val ch1 = Channel.make[Int](3)

      val ch2 = ch1.map(v => v * 2)
      val ch3 = ch2.filter(v => v != 4)

      ch1.send(1)
      ch1.send(2)
      ch1.send(3)
      ch1.close()

      val v1 = ch3.recv()
      val v2 = ch3.recv()

      assert(v1 == 2)
      assert(v2 == 6)
    }

  }

}
