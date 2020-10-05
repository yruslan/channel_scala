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

import com.github.yruslan.channel.Channel.select
import org.scalatest.WordSpec

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, SECONDS}

class ChannelSuite extends WordSpec {
  "send/recv" should {
    "work for asychronous channels in a single threaded setup" in {
      val channel1 = Channel.createAsyncChannel[Int](1)
      val channel2 = channel1

      channel1.send(10)
      val v = channel2.recv()

      assert(v == 10)
    }

    "async sent messages should arrive in FIFO order" in {
      val channel1 = Channel.createAsyncChannel[Int](5)
      val channel2 = channel1

      channel1.send(1)
      channel1.send(2)
      channel1.send(3)

      val v1 = channel2.recv()

      channel1.send(4)

      val v2 = channel2.recv()
      val v3 = channel2.recv()
      val v4 = channel2.recv()

      assert(v1 == 1)
      assert(v2 == 2)
      assert(v3 == 3)
      assert(v4 == 4)
    }

    "closed channel can still be used to receive pending messages" in {
      val channel = Channel.createAsyncChannel[Int](5)

      channel.send(1)
      channel.send(2)
      channel.send(3)

      val v1 = channel.recv()
      channel.close()
      val v2 = channel.recv()
      val v3 = channel.recv()

      assert(v1 == 1)
      assert(v2 == 2)
      assert(v3 == 3)

      val ex = intercept[IllegalStateException] {
        channel.recv()
      }

      assert(ex.getMessage.contains("Attempt to receive from a closed channel"))

      val v4 = channel.tryRecv()
      assert(v4.isEmpty)
    }

    "can't send to a closed channel" in {
      val channel = Channel.createAsyncChannel[Int](2)

      val ok = channel.trySend(1)
      assert(ok)

      channel.close()

      val ex = intercept[IllegalStateException] {
        channel.send(2)
      }

      assert(ex.getMessage.contains("Attempt to send to a closed channel"))

      val v1 = channel.tryRecv()
      val v2 = channel.tryRecv()

      assert(v1.contains(1))
      assert(v2.isEmpty)
    }

    "sync send/recv should block" in {
      val start = Instant.now.toEpochMilli
      val channel = Channel.createSyncChannel[Int]()

      val f = Future {
        Thread.sleep(100)
        channel.recv()
      }

      channel.send(100)

      val results = Await.result(f, Duration.apply(2, SECONDS))

      val finish = Instant.now.toEpochMilli

      assert(results == 100)
      assert(finish - start > 100)
    }

    "select()" should {
      "work with a single channel" in {
        val channel = Channel.createAsyncChannel[Int](2)

        channel.send(1)

        val selected = Channel.select(channel)

        assert(selected == channel)

        val value = channel.tryRecv()

        assert(value.contains(1))
      }

      "work with two channels" in {
        val channel1 = Channel.createAsyncChannel[Int](1)
        val channel2 = Channel.createAsyncChannel[Int](1)

        channel1.send(1)
        channel2.send(2)

        val selected1 = Channel.select(channel1, channel2)
        val value1 = if (selected1 == channel1) {
          channel1.recv()
        } else {
          channel2.recv()
        }

        val selected2 = Channel.select(channel1, channel2)
        val value2 = if (selected2 == channel1) {
          channel1.recv()
        } else {
          channel2.recv()
        }

        assert(value1 == 1 || value1 == 2)
        assert(value2 == 1 || value2 == 2)
        assert(value1 != value2)
      }
    }
  }

  "master/worker model" should {

    // Worker that operates on 2 channels
    def worker2(workerNum: Int, results: ListBuffer[String], channell: Channel[Int], channel2: Channel[String]): Unit = {
      while (!channell.isClosed && !channel2.isClosed) {
        select(channell, channel2) match {
          case ch1 if ch1 == channell =>
            channell.tryRecv().foreach(v => results.synchronized {
              results += s"$workerNum->i$v"
            })
          case ch2 if ch2 == channel2 =>
            channel2.tryRecv().foreach(v => results.synchronized {
              results += s"$workerNum->s$v"
            })
        }
        Thread.sleep(10)
      }
    }

    "work with one thread and two channels" in {
      val channell = Channel.createSyncChannel[Int]()
      val channel2 = Channel.createSyncChannel[String]()
      val results = new ListBuffer[String]

      val worked1Fut = Future {
        Thread.sleep(20)
        worker2(1, results, channell, channel2)
      }

      channell.send(1)
      channel2.send("A")
      channell.send(2)
      channel2.send("B")
      channell.send(3)
      channel2.send("C")

      channell.close()
      channel2.close()

      Await.result(worked1Fut, Duration.apply(4, SECONDS))

      assert(results.size == 6)
    }

    "work with two threads and two channels" in {
      val channell = Channel.createSyncChannel[Int]()
      val channel2 = Channel.createSyncChannel[String]()
      val results = new ListBuffer[String]


      val worked1Fut = Future {
        Thread.sleep(20)
        worker2(1, results, channell, channel2)
      }

      /*val worked2Fut = Future {
        Thread.sleep(30)
        worker2(2, results, channell, channel2)
      }*/

      channell.send(1)
      channel2.send("A")
      channell.send(2)
      channel2.send("B")
      channell.send(3)
      channel2.send("C")

      channell.close()
      channel2.close()

      Await.result(worked1Fut, Duration.apply(4, SECONDS))
      //Await.result(worked2Fut, Duration.apply(4, SECONDS))

      println(results.mkString(","))
    }
  }

}
