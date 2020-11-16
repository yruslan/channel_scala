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

import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.util.Random

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

  "Fairness is provided" when {
    "several channels are active, a channel is selected randomly" in {
      def balancer(input1: ReadChannel[Int], input2: ReadChannel[Int], output1: WriteChannel[Int], output2: WriteChannel[Int], finishChannel: ReadChannel[Boolean]): Unit = {
        var v: Int = 0
        var exit = false

        while (!exit) {
          select(
            input1.recver(x => v = x),
            input2.recver(x => v = x),
            finishChannel.recver(_ => exit = true)
          )

          if (!exit) {
            select(
              output1.sender(v) {},
              output2.sender(v) {}
            )
          }
        }
      }

      val results = new ListBuffer[(Int, Int)]

      def worker(num: Int, input1: ReadChannel[Int]): Unit = {
        input1.foreach(x => {
          Thread.sleep(Random.nextInt(5) + 10)
          results.synchronized {
            results.append((num, 2 * x))
          }
        })
      }

      val in1 = Channel.make[Int]
      val in2 = Channel.make[Int]

      val out1 = Channel.make[Int]
      val out2 = Channel.make[Int]
      val finish = Channel.make[Boolean]

      // Launching workers
      val w = Range(0, 4).map(i => Future {
        if (i % 2 == 0)
          worker(i, out1)
        else
          worker(i, out2)
      })

      // Launching the load balancer
      val bal = Future {
        balancer(in1, in2, out1, out2, finish)
      }

      // Sending out the work
      Range(1, 101).foreach(i => {
        if (i % 2 == 0) {
          in1.send(i)
        } else {
          in2.send(i)
        }
        Thread.sleep(10)
      })

      // Letting the balancer and the worker threads that the processing is finished
      finish.send(true)
      out1.close()
      out2.close()

      // Waiting for the futures to finish
      w.foreach(f => Await.result(f, Duration.create(4, TimeUnit.SECONDS)))
      Await.result(bal, Duration.create(4, TimeUnit.SECONDS))

      // Correctness
      assert(results.size == 100)
      assert(results.map(_._2).sum == 10100) // sum(1..100)*2 = 101*50*2 = 5050*2 = 10100

      // Fairness
      val processedBy = Range(0, 4).map(w => results.count(_._1 == w))
      assert(processedBy.min > 15)
      assert(processedBy.max < 35)
    }
  }

}
