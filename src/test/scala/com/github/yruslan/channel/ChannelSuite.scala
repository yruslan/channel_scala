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

import com.github.yruslan.channel.Channel.select
import com.github.yruslan.channel.mocks.AsyncChannelSpy
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.concurrent.{Executors, TimeUnit}
import scala.collection.mutable.ListBuffer
import scala.concurrent._
import scala.concurrent.duration.{Duration, SECONDS}

// This import is required for Scala 2.13 since it has a builtin Channel object.
import com.github.yruslan.channel.Channel

class ChannelSuite extends AnyWordSpec with BeforeAndAfterAll {
  implicit private var ec: ExecutionContextExecutor = _

  private val ex = Executors.newFixedThreadPool(16)

  override def beforeAll(): Unit = {
    super.beforeAll()
    ec = ExecutionContext.fromExecutor(ex)
  }

  override def afterAll(): Unit = {
    ex.shutdown()
    super.afterAll()
  }

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
        Thread.sleep(10L)
        ch.close()
      }

      ch.send(1)

      intercept[TimeoutException] {
        Await.result(f, Duration.create(50, TimeUnit.MILLISECONDS))
      }
    }

    "closing a synchronous channel should block until the pending message is not received" in {
      val start = Instant.now()
      val ch = Channel.make[Int](0)
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
      assert(java.time.Duration.between(start, finish).toMillis < 2000L)
    }

    "unbounded channels should non-block" in {
      val ch = Channel.makeUnbounded[Int]

      Range(0, 10000).foreach { i =>
        ch.send(i)
      }

      ch.close()

      val v1 = ch.recv()

      Range(0, 9998).foreach { i =>
        ch.recv()
      }

      val v2 = ch.recv()

      assert(v1 == 0)
      assert(v2 == 9999)
    }

    "send/foreach should handle interrupted thread" in {
      val ch = new AsyncChannelSpy[Int](1)

      val output = new ListBuffer[Int]

      val t1 = createThread {
        ch.foreach(a =>
          ch.synchronized {
            output += a
          }
        )
      }

      val t2 = createThread {
        ch.foreach(a =>
          ch.synchronized {
            output += a
          }
        )
      }

      t1.start()
      t2.start()

      ch.send(100)
      ch.send(200)
      ch.send(300)

      t1.interrupt()

      ch.send(400)
      ch.send(500)
      ch.send(600)
      ch.send(700)
      ch.close()

      t2.join(2000)
      t1.join(2000)

      assert(output.sorted.toList == List(100, 200, 300, 400, 500, 600, 700))
      assert(ch.numOfReaders == 0)
      assert(ch.numOfWriters == 0)
    }

    "send/recv should handle interrupted thread" in {
      val ch = new AsyncChannelSpy[Int](1)

      val output = new ListBuffer[Int]

      val t1 = createThread {
        ch.send(100)
        ch.send(200)
        ch.send(300)
      }

      val t2 = createThread {
        ch.send(400)
        Thread.sleep(30)
        ch.send(500)
        ch.send(600)
        ch.send(700)
        ch.close()
      }

      t1.start()
      t2.start()

      output += ch.recv()

      t1.interrupt()

      ch.foreach(v => output += v)

      t2.join(2000)
      t1.join(2000)

      assert(output.contains(400))
      assert(output.contains(500))
      assert(output.contains(600))
      assert(output.contains(700))
      assert(ch.numOfReaders == 0)
      assert(ch.numOfWriters == 0)
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

        val ok1 = ch.trySend("test1", Duration.Zero)
        val ok2 = ch.trySend("test2", Duration.Zero)

        assert(ok1)
        assert(!ok2)
      }

      "return false if the channel is closed" in {
        val ch = Channel.make[Int](1)
        ch.close()

        val ok = ch.trySend(2)

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

        val start = Instant.now()
        val v = ch.tryRecv(Duration.create(200, TimeUnit.MILLISECONDS))
        Await.result(f, Duration.apply(2, SECONDS))
        val finish = Instant.now()

        assert(v.isDefined)
        assert(v.contains("test"))
        assert(java.time.Duration.between(start, finish).toMillis >= 10L)
        assert(java.time.Duration.between(start, finish).toMillis < 200L)
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
        assert(processed.size == 3)
        assert(processed.head == "test1")
        assert(processed(1) == "test2")
        assert(processed(2) == "test3")
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

      val start = Instant.now()
      ch.send(1)
      ch.send(2)
      ch.send(3)
      ch.close()
      val finish = Instant.now()

      Await.ready(fut, Duration.create(2, TimeUnit.SECONDS))

      assert(values.toList == 1 :: 2 :: 3 :: Nil)
      assert(java.time.Duration.between(start, finish).toMillis < 2000L)
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

      val ok = Channel.select(channel.sender(1) {})
      val value = channel.tryRecv()

      assert(ok)
      assert(value.contains(1))
    }

    "ping pong messages between 2 workers" in {
      for (_ <- Range(0, 100)) {
        val actions = new StringBuffer()

        /* Full qualified name 'com.github.yruslan.channel.Channel' is used here to make IntelliJ IDEA happy. */
        def worker(workerNum: Int, ch1: com.github.yruslan.channel.Channel[Int], ch2: com.github.yruslan.channel.Channel[Int]): Unit = {
          for (i <- Range(0, 10)) {
            val k = select(
              ch1.recver(n => {
                actions.append(s"R$workerNum$n-")
              }),
              ch2.sender(i) {
                actions.append(s"S$workerNum$i-")
              }
            )
            if (!k) throw new IllegalArgumentException("Failing the worker")
          }
        }

        val channel1 = Channel.make[Int]
        val channel2 = Channel.make[Int]

        val fut1 = Future {
          worker(1, channel1, channel2)
        }

        val fut2 = Future {
          worker(2, channel2, channel1)
        }

        Await.result(fut1, Duration.apply(4, SECONDS))
        Await.result(fut2, Duration.apply(4, SECONDS))

        // 10 messages sent and received, by 2 workers
        assert(actions.toString.length == 80)
      }
    }

    "work with two channels" in {
      val channel1 = Channel.make[Int](1)
      val channel2 = Channel.make[Int](1)

      channel1.send(1)
      channel2.send(2)

      var value1 = 0
      var value2 = 0

      val selected1 = Channel.select(
        channel1.recver { v => value1 = v },
        channel2.recver { v => value1 = v }
      )

      val selected2 = Channel.select(
        channel1.recver { v => value2 = v },
        channel2.recver { v => value2 = v })

      assert(selected1)
      assert(selected2)
      assert(value1 == 1 || value1 == 2)
      assert(value2 == 1 || value2 == 2)
      assert(value1 != value2)
    }

    "work with when sending and receiving in the same select statement" in {
      val channel = Channel.make[Int]

      var value1 = 0
      var value2 = 0

      val fut = Future {
        channel.send(1)
        value1 = channel.recv()
      }

      val selected1 = Channel.select(
        channel.sender(2) {},
        channel.recver { v => value2 = v }
      )

      val selected2 = Channel.select(
        channel.sender(2) {},
        channel.recver { v => value2 = v }
      )

      Await.result(fut, Duration.apply(4, SECONDS))

      assert(selected1)
      assert(selected2)
      assert(value1 == 2)
      assert(value2 == 1)
    }
  }

  "select() for synchronous channels" should {
    "select should not send if there is no receiver" in {
      val channel = Channel.make[Int]

      val t1 = createThread{
        Channel.select(
          channel.sender(1) {}
        )

        channel.recv()
        fail("Should not execute here")
      }

      t1.start()
      t1.join(200)
      assert(t1.isAlive)
      t1.interrupt()
    }

    "select should not send to the same thread" in {
      val channel = Channel.make[Int]

      val t1 = createThread {
        var output = 0
        Channel.select(
          channel.sender(1) {},
          channel.recver(v => output = v)
        )

        fail("Should not execute here")
      }

      t1.start()
      t1.join(200)
      assert(t1.isAlive)
      t1.interrupt()
    }

    "select should execute default selector if others are not available" in {
      val channel = Channel.make[Int]

      var reached = false

      var output = 0
      Channel.select(
        channel.sender(1) {},
        channel.recver(v => output = v),
        channel.default{ reached = true }
      )

      assert(reached)
    }

    "select should throw an exception if more than one default section is encountered" in {
      val channel = Channel.make[Int]
      var reached = false
      var output = 0

      assertThrows[IllegalArgumentException] {
        Channel.select(
          channel.sender(1) {},
          channel.recver(v => output = v),
          channel.default {
            reached = true
          },
          channel.default {
            reached = true
          }
        )
      }
    }

    "ping pong between 2 workers with single channel" in {
      for (_ <- Range(0, 100)) {
        val actions = new StringBuffer()

        /* Full qualified name 'com.github.yruslan.channel.Channel' is used here to make IntelliJ IDEA happy. */
        def worker(workerNum: Int, ch: com.github.yruslan.channel.Channel[Int]): Unit = {
          for (i <- Range(0, 10)) {
            val k = select(
              ch.recver(n => {
                actions.append(s"R$workerNum$n-")
              }),
              ch.sender(i) {
                actions.append(s"S$workerNum$i-")
              }
            )
            if (!k) throw new IllegalArgumentException("Failing the worker")
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

        //println(actions.toString)

        // 10 messages sent and received, by 2 workers
        assert(actions.toString.length == 80)
      }
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

        var value1 = 0
        val selected = Channel.trySelect(Duration.create(200, TimeUnit.MILLISECONDS),
          channel.recver(v => value1 = v)
        )

        assert(selected)
        assert(value1 == 1)
      }

      "timeout is expired" in {
        val channel = Channel.make[Int](1)

        Future {
          Thread.sleep(50)
          channel.send(1)
        }

        var value1 = 0
        val selected = Channel.trySelect(Duration.create(1, TimeUnit.MILLISECONDS),
          channel.recver(v => value1 = v)
        )

        assert(!selected)
        assert(value1 == 0)
      }
    }

    "handle zero timeouts" when {
      "when data is available" in {
        val channel = Channel.make[Int](1)

        channel.send(1)

        var value1 = 0
        val selected = Channel.trySelect(Duration.Zero,
          channel.recver(v => value1 = v)
        )

        assert(selected)
        assert(value1 == 1)
      }

      "when data is not available" in {
        val channel = Channel.make[Int](1)

        var value1 = 0
        val selected = Channel.trySelect(Duration.Zero,
          channel.recver(v => value1 = v)
        )

        assert(!selected)
        assert(value1 == 0)
      }
    }

    "handle infinite timeouts" when {
      "when data is available" in {
        val channel = Channel.make[Int](1)

        channel.send(1)

        var value1 = 0
        val selected = Channel.trySelect(Duration.Inf,
          channel.recver(v => value1 = v)
        )

        assert(selected)
        assert(value1 == 1)
      }

      "when data is not available" in {
        val channel = Channel.make[Int](1)

        val fut = Future {
          Channel.trySelect(Duration.Inf,
            channel.recver(v => {}))
        }

        intercept[TimeoutException] {
          Await.ready(fut, Duration.create(50, TimeUnit.MILLISECONDS))
        }
      }
    }
  }

  "toList" should {
    "covert a channel to a list" in {
      val ch1 = Channel.make[Int](3)

      ch1.send(1)
      ch1.send(2)
      ch1.send(3)
      ch1.close()

      val lst = ch1.toList

      assert(lst == List(1, 2, 3))
    }

    "convert a mapped filtered channel to a list" in {
      val ch1 = Channel.make[Int](3)

      val ch2 = ch1
        .map(v => v * 2)
        .filter(v => v != 4)

      ch1.send(1)
      ch1.send(2)
      ch1.send(3)
      ch1.close()

      val lst = ch2.toList

      assert(lst == List(2, 6))
    }
  }
  "for comprehension" should {
    "test for comprehension with yield" in {
      val ch1 = Channel.make[Int](3)

      val ch2 = ch1
        .map(v => v * 2)
        .filter(v => v != 4)

      ch1.send(1)
      ch1.send(2)
      ch1.send(3)
      ch1.close()

      val outputChannel = for {
        a <- ch2
        if a > 5
      } yield a

      assert(outputChannel.recv() == 6)
    }

    "test for comprehension with foreach" in {
      val ch1 = Channel.make[Int](3)

      val ch2 = ch1
        .map(v => v * 2)
        .filter(v => v != 4)

      ch1.send(1)
      ch1.send(2)
      ch1.send(3)
      ch1.close()

      var out = 0
      for {
        a <- ch2
        if a > 5
      } out = a

      assert(out == 6)
    }

    "test for comprehension with 2 channels" in {
      val ch1 = Channel.make[Int](3)
      val ch2 = Channel.make[Int](3)

      val ch3 = ch1
        .map(v => v * 2)
        .filter(v => v != 4)

      ch1.send(1)
      ch1.send(2)
      ch1.send(3)
      ch1.close()

      ch2.send(100)
      ch2.send(200)
      ch2.send(300)
      ch2.close()

      var out = 0
      for {
        a <- ch2
        b <- ch3
        if a > 5
        if b > 5
      } out = a + b

      assert(out == 106)
    }
  }

  "master/worker model" should {
    // Worker that operates on 2 channels
    def worker2(workerNum: Int,
                results: ListBuffer[String],
                channell: ReadChannel[Int],
                channel2: ReadChannel[String]): Unit = {
      while (!channell.isClosed && !channel2.isClosed) {
        select(
          channell.recver { v =>
            results.synchronized {
              results += s"$workerNum->i$v"
            }
          },
          channel2.recver { v =>
            results.synchronized {
              results += s"$workerNum->s$v"
            }
          })
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
  }

  "channel casting" should {
    "support covariance" in {
      val ch1 = Channel.make[String](1)
      val ch2: ReadChannel[Any] = ch1
      ch1.send("hello")
      assert(ch2.recv() == "hello")
    }

    "support contravariance" in {
      val ch1 = Channel.make[Any](1)
      val ch2: WriteChannel[String] = ch1
      ch2.send("hello")
      assert(ch1.recv() == "hello")
    }
  }

  private def createThread(action: => Unit) = {
    // Creating thread in the Scala 2.11 compatible way.
    // Please do not remove 'new Runnable'
    new Thread(new Runnable {
      def run(): Unit = action
    })
  }
}
