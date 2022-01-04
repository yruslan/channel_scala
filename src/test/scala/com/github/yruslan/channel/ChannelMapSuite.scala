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
import com.github.yruslan.channel.Channel

class ChannelMapSuite extends AnyWordSpec {
  implicit private val ec: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))

  "Channel.map()" should {
    "map output with recv()" in {
      val ch1 = Channel.make[Int](2)

      val ch2 = ch1.map(v => v.toString)

      ch1.send(1)
      ch1.send(2)
      ch1.close()

      val s1 = ch2.recv()
      val s2 = ch2.recv()

      assert(s1 == "1")
      assert(s2 == "2")
    }

    "map output with tryRecv()" in {
      val ch1 = Channel.make[Int](2)

      val ch2 = ch1.map(v => v.toString)

      val s1 = ch2.tryRecv()

      ch1.send(1)
      ch1.close()

      val s2 = ch2.tryRecv()

      assert(s1.isEmpty)
      assert(s2.contains("1"))
    }

    "map output with tryRecv(duration)" in {
      val ch1 = Channel.make[Int](2)

      val ch2 = ch1.map(v => v.toString)

      val s1 = ch2.tryRecv(Duration(2, MILLISECONDS))

      ch1.send(1)
      ch1.close()

      val s2 = ch2.tryRecv(Duration(2, MILLISECONDS))

      assert(s1.isEmpty)
      assert(s2.contains("1"))
    }

    "map output with recver()" in {
      val ch1 = Channel.make[Int](2)

      val ch2 = ch1.map(v => v.toString)

      ch1.send(1)
      ch1.close()

      var v1 = ""
      Channel.select(ch2.recver{v => v1 = v})

      assert(v1 == "1")
    }

    "map output with fornew()" in {
      val ch1 = Channel.make[Int](2)

      val ch2 = ch1.map(v => v.toString)

      ch1.send(1)

      var v1 = ""

      ch2.fornew(v => v1 = v)

      assert(v1 == "1")
    }

    "map output with foreach()" in {
      val ch1 = Channel.make[Int](2)

      val ch2 = ch1.map(v => v.toString)

      ch1.send(1)
      ch1.close()

      var v1 = ""

      ch2.foreach(v => v1 = v)

      assert(v1 == "1")
    }

    "map output with map(map())" in {
      val ch1 = Channel.make[Int](2)

      val ch2 = ch1.map(v => v.toString)

      val ch3 = ch2.map(v => v.toInt + 1)

      ch1.send(1)
      ch1.send(2)
      ch1.close()

      val v1 = ch3.recv()
      val v2 = ch3.recv()

      assert(v1 == 2)
      assert(v2 == 3)
    }

  }

}
