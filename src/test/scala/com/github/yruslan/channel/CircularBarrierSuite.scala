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

class CircularBarrierSuite extends AnyWordSpec {
  "CircularBarrier" should {
    "handle barrier synchronization among multiple threads" in {
      val threadCount = 10
      val output = new Array[Int](10)
      val barrier = CircularBarrier(threadCount)
      val threads = (0 until threadCount).map { id =>
        new Thread {
          override def run(): Unit = {
            var i = 0
            while(i < 1000) {
              output(id) += 1
              barrier.await()
              i += 1
            }
          }
        }
      }

      threads.foreach(_.start())
      threads.foreach(_.join())

      assert(output.forall(_ == 1000))
    }

    "throw an exception when the capacity is zero" in {
      assertThrows[IllegalArgumentException](CircularBarrier(0))
    }

    "throw an exception when the capacity is negative" in {
      assertThrows[IllegalArgumentException](CircularBarrier(-10))
    }
  }
}
