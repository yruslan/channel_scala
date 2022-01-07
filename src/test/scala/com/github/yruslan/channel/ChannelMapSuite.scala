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
