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

import java.time.Instant
import java.util.concurrent.{Executors, TimeUnit}

import org.scalatest.wordspec.AnyWordSpec

import com.github.yruslan.channel.Channel.select

import scala.collection.mutable.ListBuffer
import scala.concurrent._
import scala.concurrent.duration.Duration

// This import is required for Scala 2.13 since it has a builtin Channel object.
import com.github.yruslan.channel.Channel

class GuaranteesSuite extends AnyWordSpec {
  implicit private val ec: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))

  "Progress guarantees are provided" when {
    "one channel always has messages to process" in {
      // Here we test that when
      // * There a worker processing messages from 2 channels and.
      // * Processing a message takes more time than a new message to arrive in each channel.
      // Then
      // * Messages from both channels have a chance to be processed, none of the channels
      //   gets priority.
      val ch1 = Channel.make[Int](20)
      val ch2 = Channel.make[Int](20)

      val processed1Times = new ListBuffer[Instant]
      val processed2Times = new ListBuffer[Instant]

      val fut = Future {
        while (!ch1.isClosed && !ch2.isClosed)
          select(
            ch1.recver { _ =>
              processed1Times += Instant.now()
              Thread.sleep(30)
            },
            ch2.recver { _ =>
              processed2Times += Instant.now()
              Thread.sleep(30)
            })
      }

      for (_ <- Range(0, 20)) {
        ch1.send(1)
        ch2.send(2)
        Thread.sleep(20)
      }
      ch1.close()
      ch2.close()

      Await.result(fut, Duration.create(2, TimeUnit.SECONDS))

      // At least one message from ch2 should be processed
      assert(processed2Times.nonEmpty)
    }
  }

}
