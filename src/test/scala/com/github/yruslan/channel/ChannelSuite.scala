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

import org.scalatest.WordSpec

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

  }

}
