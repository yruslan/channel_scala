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

import com.github.yruslan.channel.Channel.selectNew
import org.scalatest.wordspec.AnyWordSpec

// This import is required for Scala 2.13 since it has a builtin Channel object.
import com.github.yruslan.channel.Channel

import com.github.yruslan.channel.Channel.select

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.{Duration, SECONDS}
import scala.concurrent._

class ChannelSuite extends AnyWordSpec {
  implicit private val ec: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(12))

  "send() and recv()" should {
    "work for asynchronous channels in a single threaded setup" in {
      val ch1 = Channel.make[Int](1)
      val ch2 = ch1

      ch1.send(10)
      val v = ch2.recv()

      assert(v == 10)
    }

    "async sent messages should arrive in FIFO order" in {
      val ch1 = Channel.make[Int](5)
      val ch2 = ch1

      ch1.send(1)
      ch1.send(2)
      ch1.send(3)

      val v1 = ch2.recv()

      ch1.send(4)

      val v2 = ch2.recv()
      val v3 = ch2.recv()
      val v4 = ch2.recv()

      assert(v1 == 1)
      assert(v2 == 2)
      assert(v3 == 3)
      assert(v4 == 4)
    }

    "closed channel can still be used to receive pending messages" in {
      val ch = Channel.make[Int](5)

      ch.send(1)
      ch.send(2)
      ch.send(3)

      val v1 = ch.recv()
      ch.close()
      val v2 = ch.recv()
      val v3 = ch.recv()

      assert(v1 == 1)
      assert(v2 == 2)
      assert(v3 == 3)
    }

    "closing a synchronous channel should block if the pending message is not received" in {
      val ch = Channel.make[Int]

      val f = Future {
        ch.close()
      }

      ch.send(1)

      intercept[TimeoutException] {
        Await.result(f, Duration.create(50, TimeUnit.MILLISECONDS))
      }
    }

    "closing a synchronous channel should block until the pending message is not received" in {
      val start = Instant.now()
      val ch = Channel.make[Int]
      var v: Option[Int] = None

      Future {
        Thread.sleep(120L)
        v = Option(ch.recv())
      }

      Future {
        ch.send(1)
      }

      val f2 = Future {
        Thread.sleep(50L)
        ch.close()
      }

      Await.result(f2, Duration.create(2, TimeUnit.SECONDS))
      val finish = Instant.now()

      assert(v.nonEmpty)
      assert(v.contains(1))
      assert(java.time.Duration.between(start, finish).toMillis >= 60L)
      assert(java.time.Duration.between(start, finish).toMillis < 2000L)
    }

    "reading a closed channel should thrown an exception" in {
      val ch = Channel.make[Int](5)

      ch.send(1)

      val v1 = ch.recv()
      ch.close()

      assert(v1 == 1)

      val ex = intercept[IllegalStateException] {
        ch.recv()
      }

      assert(ex.getMessage.contains("Attempt to receive from a closed channel"))

      val v4 = ch.tryRecv()
      assert(v4.isEmpty)
    }

    "can't send to a closed channel" in {
      val ch = Channel.make[Int](2)

      val ok = ch.trySend(1)
      assert(ok)

      ch.close()

      val ex = intercept[IllegalStateException] {
        ch.send(2)
      }

      assert(ex.getMessage.contains("Attempt to send to a closed channel"))

      val v1 = ch.tryRecv()
      val v2 = ch.tryRecv()

      assert(v1.contains(1))
      assert(v2.isEmpty)
    }

    "sync send/recv should block" in {
      val start = Instant.now
      val ch = Channel.make[Int]

      val f = Future {
        Thread.sleep(100)
        ch.recv()
      }

      ch.send(100)

      val results = Await.result(f, Duration.apply(2, SECONDS))

      val finish = Instant.now

      assert(results == 100)
      assert(java.time.Duration.between(start, finish).toMillis >= 100L)
    }
  }

  "trySend() for sync channels" should {
    "handle non-blocking way" when {
      "data is available" in {
        val ch = Channel.make[String]

        Future {
          ch.recv()
        }

        Thread.sleep(30)
        val ok = ch.trySend("test", Duration.Zero)

        assert(ok)
      }

      "data is not available" in {
        val ch = Channel.make[String]

        ch.trySend("test1", Duration.Zero)
        val ok = ch.trySend("test2", Duration.Zero)

        assert(!ok)
      }

      "return false if the channel is closed" in {
        val ch = Channel.make[Int]
        ch.close()

        val ok = ch.trySend(2)

        assert(!ok)
      }
    }

    "handle finite timeouts" when {
      "timeout is not expired" in {
        val ch = Channel.make[String]

        val f = Future {
          Thread.sleep(10)
          ch.recv()
        }

        ch.trySend("test1", Duration.Zero)
        val ok = ch.trySend("test", Duration.create(200, TimeUnit.MILLISECONDS))
        Await.result(f, Duration.apply(2, SECONDS))

        assert(ok)
      }

      "timeout is expired" in {
        val ch = Channel.make[String]

        val f = Future {
          Thread.sleep(100)
          ch.recv()
        }

        val ok = ch.trySend("test2", Duration.create(10, TimeUnit.MILLISECONDS))
        ch.send("test1")

        Await.result(f, Duration.apply(2, SECONDS))

        assert(!ok)
      }
    }

    "handle infinite timeouts" when {
      "data is ready" in {
        val ch = Channel.make[String]

        val f = Future {
          Thread.sleep(10)
          ch.recv()
        }

        val ok = ch.trySend("test", Duration.Inf)
        Await.result(f, Duration.apply(2, SECONDS))

        assert(ok)
      }

      "data is not ready" in {
        val ch = Channel.make[String]

        val f = Future {
          ch.trySend("test1", Duration.Zero)
          ch.trySend("test2", Duration.Inf)
        }

        intercept[TimeoutException] {
          Await.result(f, Duration.create(50, TimeUnit.MILLISECONDS))
        }
      }
    }
  }

  "trySend() for async channels" should {
    "handle non-blocking way" when {
      "data is available" in {
        val ch = Channel.make[String](1)

        val ok = ch.trySend("test", Duration.Zero)

        assert(ok)
      }

      "data is not available" in {
        val ch = Channel.make[String](1)

        ch.trySend("test1", Duration.Zero)
        val ok = ch.trySend("test2", Duration.Zero)

        assert(!ok)
      }
    }

    "handle finite timeouts" when {
      "timeout is not expired" in {
        val ch = Channel.make[String](1)

        val f = Future {
          Thread.sleep(10)
          ch.recv()
        }

        ch.trySend("test1", Duration.Zero)
        val ok = ch.trySend("test", Duration.create(200, TimeUnit.MILLISECONDS))
        Await.result(f, Duration.apply(2, SECONDS))

        assert(ok)
      }

      "timeout is expired" in {
        val ch = Channel.make[String](1)

        val f = Future {
          Thread.sleep(100)
          ch.recv()
        }

        ch.trySend("test1", Duration.Zero)
        val ok = ch.trySend("test2", Duration.create(10, TimeUnit.MILLISECONDS))

        Await.result(f, Duration.apply(2, SECONDS))

        assert(!ok)
      }
    }

    "handle infinite timeouts" when {
      "data is ready" in {
        val ch = Channel.make[String](1)

        val f = Future {
          Thread.sleep(10)
          ch.recv()
        }

        ch.trySend("test1", Duration.Zero)
        val ok = ch.trySend("test", Duration.Inf)
        Await.result(f, Duration.apply(2, SECONDS))

        assert(ok)
      }

      "data is not ready" in {
        val ch = Channel.make[String](1)

        val f = Future {
          ch.trySend("test1", Duration.Zero)
          ch.trySend("test2", Duration.Inf)
        }

        intercept[TimeoutException] {
          Await.ready(f, Duration.create(50, TimeUnit.MILLISECONDS))
        }
      }
    }
  }

  "tryRecv() for sync channels" should {
    "handle non-blocking way" when {
      "data is available" in {
        val ch = Channel.make[String]

        Future {
          ch.send("test")
        }
        Thread.sleep(30)
        val v = ch.tryRecv(Duration.Zero)

        assert(v.nonEmpty)
        assert(v.contains("test"))
      }

      "data is not available" in {
        val ch = Channel.make[String]

        val v = ch.tryRecv(Duration.Zero)

        assert(v.isEmpty)
      }
    }

    "handle finite timeouts" when {
      "timeout is not expired" in {
        val ch = Channel.make[String]

        val f = Future {
          Thread.sleep(10)
          ch.trySend("test", Duration.Zero)
        }

        val v = ch.tryRecv(Duration.create(200, TimeUnit.MILLISECONDS))
        Await.result(f, Duration.apply(2, SECONDS))

        assert(v.isDefined)
        assert(v.contains("test"))
      }

      "timeout is expired" in {
        val ch = Channel.make[String]

        val start = Instant.now()
        val v = ch.tryRecv(Duration.create(10, TimeUnit.MILLISECONDS))
        val finish = Instant.now()

        assert(v.isEmpty)
        assert(java.time.Duration.between(start, finish).toMillis >= 10L)
      }
    }

    "handle infinite timeouts" when {
      "data is ready" in {
        val ch = Channel.make[String]

        val f = Future {
          Thread.sleep(10)
          ch.trySend("test", Duration.Zero)
        }

        val start = Instant.now()
        val v = ch.tryRecv(Duration.Inf)
        val finish = Instant.now()

        Await.result(f, Duration.apply(2, SECONDS))

        assert(v.isDefined)
        assert(v.contains("test"))
        assert(java.time.Duration.between(start, finish).toMillis >= 10L)
        assert(java.time.Duration.between(start, finish).toMillis < 2000L)
      }

      "data is not ready" in {
        val ch = Channel.make[String]

        val f = Future {
          ch.tryRecv(Duration.Inf)
        }

        intercept[TimeoutException] {
          Await.ready(f, Duration.create(50, TimeUnit.MILLISECONDS))
        }
      }
    }
  }

  "tryRecv() for async channels" should {
    "handle non-blocking way" when {
      "data is available" in {
        val ch = Channel.make[String](1)

        ch.send("test")
        val v = ch.tryRecv(Duration.Zero)

        assert(v.nonEmpty)
        assert(v.contains("test"))
      }

      "data is not available" in {
        val ch = Channel.make[String](1)

        val v = ch.tryRecv(Duration.Zero)

        assert(v.isEmpty)
      }
    }

    "handle finite timeouts" when {
      "timeout is not expired" in {
        val ch = Channel.make[String](1)

        val f = Future {
          Thread.sleep(10)
          ch.send("test")
        }

        val v = ch.tryRecv(Duration.create(200, TimeUnit.MILLISECONDS))
        Await.result(f, Duration.apply(2, SECONDS))

        assert(v.isDefined)
        assert(v.contains("test"))
      }

      "timeout is expired" in {
        val ch = Channel.make[String](1)

        val start = Instant.now()
        val v = ch.tryRecv(Duration.create(10, TimeUnit.MILLISECONDS))
        val finish = Instant.now()

        assert(v.isEmpty)
        assert(java.time.Duration.between(start, finish).toMillis >= 10L)
      }
    }

    "handle infinite timeouts" when {
      "data is ready" in {
        val ch = Channel.make[String](1)

        val f = Future {
          Thread.sleep(10)
          ch.send("test")
        }

        val start = Instant.now()
        val v = ch.tryRecv(Duration.Inf)
        val finish = Instant.now()

        Await.result(f, Duration.apply(2, SECONDS))

        assert(v.isDefined)
        assert(v.contains("test"))
        assert(java.time.Duration.between(start, finish).toMillis >= 10L)
        assert(java.time.Duration.between(start, finish).toMillis < 2000L)
      }

      "data is not ready" in {
        val ch = Channel.make[String]

        val f = Future {
          ch.tryRecv(Duration.Inf)
        }

        intercept[TimeoutException] {
          Await.ready(f, Duration.create(50, TimeUnit.MILLISECONDS))
        }
      }
    }
  }

  "fornew()" should {
    "handle synchronous channels" when {
      "there is data" in {
        val ch = Channel.make[String]

        val processed = ListBuffer[String]()

        Future {
          ch.send("test")
        }

        Thread.sleep(30)
        ch.fornew(v => processed += v)

        assert(processed.nonEmpty)
        assert(processed.size == 1)
        assert(processed.head == "test")
      }

      "there are no data" in {
        val ch = Channel.make[String]

        val processed = ListBuffer[String]()

        ch.fornew(v => processed += v)

        assert(processed.isEmpty)
      }
    }

    "handle asynchronous channels" when {
      "there is data" in {
        val ch = Channel.make[String](3)

        val processed = ListBuffer[String]()

        ch.send("test1")
        ch.send("test2")
        ch.send("test3")

        ch.fornew(v => processed += v)

        assert(processed.nonEmpty)
        assert(processed.size == 1)
        assert(processed.head == "test1")
      }

      "there are no data" in {
        val ch = Channel.make[String](3)

        val processed = ListBuffer[String]()

        ch.send("test1")
        ch.recv()

        ch.fornew(v => processed += v)

        assert(processed.isEmpty)
      }
    }
  }

  "foreach()" should {
    "iterate through all values of a synchronous channel" in {
      val ch = Channel.make[Int]
      val values = new ListBuffer[Int]

      val fut = Future {
        ch.foreach(v => values.synchronized {
          values += v
        })
      }

      ch.send(1)
      ch.send(2)
      ch.send(3)
      ch.close()

      Await.ready(fut, Duration.create(2, TimeUnit.SECONDS))

      assert(values.toList == 1 :: 2 :: 3 :: Nil)
    }

    "iterate through all values of a asynchronous channel" in {
      val ch = Channel.make[Int](3)
      ch.send(1)
      ch.send(2)
      ch.send(3)
      ch.close()

      val values = new ListBuffer[Int]
      ch.foreach(v => values += v)
      assert(values.toList == 1 :: 2 :: 3 :: Nil)
    }
  }

  "select()" should {
    "work with a single channel" in {
      val channel = Channel.make[Int](2)

      channel.send(1)

      Channel.selectNew(channel.sender(1) {})

      val value = channel.tryRecv()

      assert(value.contains(1))
    }

    "work with two channels" in {
      val channel1 = Channel.make[Int](1)
      val channel2 = Channel.make[Int](1)

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

  "trySelect()" should {
    "handle finite timeouts" when {
      "timeout is not expired" in {
        val channel = Channel.make[Int](1)

        Future {
          Thread.sleep(1)
          channel.send(1)
        }

        val selected = Channel.trySelect(Duration.create(200, TimeUnit.MILLISECONDS), channel)

        assert(selected.contains(channel))
      }

      "timeout is expired" in {
        val channel = Channel.make[Int](1)

        Future {
          Thread.sleep(50)
          channel.send(1)
        }

        val selected = Channel.trySelect(Duration.create(1, TimeUnit.MILLISECONDS), channel)

        assert(selected.isEmpty)
      }
    }

    "handle zero timeouts" when {
      "when data is available" in {
        val channel = Channel.make[Int](1)

        channel.send(1)

        val selected = Channel.trySelect(channel)

        assert(selected.contains(channel))
      }

      "when data is not available" in {
        val channel = Channel.make[Int](1)

        val selected = Channel.trySelect(channel)

        assert(selected.isEmpty)
      }
    }

    "handle infinite timeouts" when {
      "when data is available" in {
        val channel = Channel.make[Int](1)

        channel.send(1)

        val selected = Channel.trySelect(Duration.Inf, channel)

        assert(selected.contains(channel))
      }

      "when data is not available" in {
        val channel = Channel.make[Int](1)

        val fut = Future {
          Channel.trySelect(Duration.Inf, channel)
        }

        intercept[TimeoutException] {
          Await.ready(fut, Duration.create(50, TimeUnit.MILLISECONDS))
        }
      }
    }

  }

  "master/worker model" should {
    // Worker that operates on 2 channels
    def worker2(workerNum: Int, results: ListBuffer[String], channell: Channel[Int], channel2: Channel[String]): Unit = {
      while (!channell.isClosed && !channel2.isClosed) {
        select(channell, channel2) match {
          case `channell` =>
            channell.tryRecv().foreach(v => results.synchronized {
              results += s"$workerNum->i$v"
            })
          case `channel2` =>
            channel2.tryRecv().foreach(v => results.synchronized {
              results += s"$workerNum->s$v"
            })
        }
        Thread.sleep(10)
      }
    }

    "work with one thread and two channels" in {
      val channell = Channel.make[Int]
      val channel2 = Channel.make[String]
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
      val channell = Channel.make[Int]
      val channel2 = Channel.make[String]
      val results = new ListBuffer[String]

      val worked1Fut = Future {
        Thread.sleep(20)
        worker2(1, results, channell, channel2)
      }

      val worked2Fut = Future {
        Thread.sleep(30)
        worker2(2, results, channell, channel2)
      }

      channell.send(1)
      channel2.send("A")
      channell.send(2)
      channel2.send("B")
      channell.send(3)
      channel2.send("C")

      Thread.sleep(300)
      channell.close()
      channel2.close()

      Await.result(worked1Fut, Duration.apply(4, SECONDS))
      Await.result(worked2Fut, Duration.apply(4, SECONDS))

      assert(results.size == 6)
    }

    "handle a situation when one worked doen't handle a received message" in {
      val channel1 = Channel.make[String](3)
      val results = new ListBuffer[String]
      val start = Instant.now

      Future {
        select(channel1)
      }

      val worked2Fut = Future {
        while (!channel1.isClosed) {
          select(channel1)
          channel1.fornew(s => results += s)
        }
      }

      Thread.sleep(20)
      channel1.send("a")
      channel1.send("b")
      channel1.send("c")
      channel1.close()

      Await.result(worked2Fut, Duration.apply(2, SECONDS))
      val finish = Instant.now

      assert(results.toList == "a" :: "b" :: "c" :: Nil)
      assert(java.time.Duration.between(start, finish).toMillis < 2000L)
    }
  }

  "selectNew()" should {
    "work with a single channel" in {
      val channel = Channel.make[Int](2)

      val ok = Channel.selectNew(channel.sender(1) {})

      assert(ok)

      val value = channel.tryRecv()

      assert(value.contains(1))
    }

    "ping pong messages between 2 workers" in {
      val actions = new StringBuffer()

      def worker(workerNum: Int, ch: Channel[Int]): Unit = {
        for (i <- Range(0, 10)) {
          val k = selectNew(
            ch.recver(n => {
              actions.append(s"R$workerNum$i-")
            }),
            ch.sender(i){
              actions.append(s"S$workerNum$i-")
            }
          )
          if (!k) throw new IllegalArgumentException("sss")
        }
      }

      val channel = Channel.make[Int]

      val fut1 = Future {
        worker(1, channel)
      }

      val fut2 = Future {
        worker(2, channel)
      }

      Await.result(fut1, Duration.apply(4, SECONDS))
      Await.result(fut2, Duration.apply(4, SECONDS))

      // 10 messages sent and received, by 2 workers
      assert(actions.toString.length == 80)
    }

  }

}
