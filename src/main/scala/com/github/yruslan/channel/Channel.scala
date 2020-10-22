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
import java.util.concurrent.{Semaphore, TimeUnit}
import java.util.concurrent.locks.ReentrantLock

import com.github.yruslan.channel.impl.SimpleLinkedList

import scala.collection.mutable
import scala.concurrent.duration.Duration

class Channel[T](val maxCapacity: Int) extends ReadChannel[T] with WriteChannel[T] {
  private var readers: Int = 0
  private var writers: Int = 0
  private var closed = false
  private val q = new mutable.Queue[T]
  private val waiters = new SimpleLinkedList[Semaphore]
  private var syncValue: Option[T] = None

  // Scala & Java monitors are designed so each object can act as a mutex and a condition variable.
  // But this makes impossible to use a single lock for more than one condition.
  // So a lock from [java.util.concurrent.locks] is used instead. It allows to have several condition
  // variables that use a single lock.
  private val lock = new ReentrantLock()
  private val crd = lock.newCondition()
  private val cwr = lock.newCondition()

  override def close(): Unit = {
    lock.lock()
    try {
      if (!closed) {
        closed = true
        waiters.foreach(w => w.release())
        crd.signalAll()
        cwr.signalAll()
        if (maxCapacity == 0) {
          while (syncValue.nonEmpty) {
            cwr.await()
          }
        }
      }
    } finally {
      lock.unlock()
    }
  }

  override def send(value: T): Unit = {
    lock.lock()
    try {
      if (closed) {
        throw new IllegalStateException(s"Attempt to send to a closed channel.")
      }

      writers += 1
      if (maxCapacity == 0) {
        // Synchronous send
        while (syncValue.nonEmpty && !closed) {
          cwr.await()
        }
        if (!closed) {
          syncValue = Option(value)
          notifyReaders()

          while (syncValue.nonEmpty && !closed) {
            cwr.await()
          }
          cwr.signal()
        }
      } else {
        // Asynchronous send
        while (q.size == maxCapacity && !closed) {
          cwr.await()
        }

        if (!closed) {
          q.enqueue(value)
        }
        notifyReaders()
      }
      writers -= 1
    } finally {
      lock.unlock()
    }
  }

  override def trySend(value: T): Boolean = {
    lock.lock()
    try {
      if (closed) {
        false
      } else {
        if (maxCapacity == 0) {
          if (syncValue.isDefined) {
            false
          } else {
            syncValue = Option(value)
            notifyReaders()
            true
          }
        } else {
          if (q.size == maxCapacity) {
            false
          } else {
            q.enqueue(value)
            notifyReaders()
            true
          }
        }
      }
    } finally {
      lock.unlock()
    }
  }

  override def trySend(value: T, timeout: Duration): Boolean = {
    if (timeout == Duration.Zero) {
      return trySend(value)
    }

    val infinite = !timeout.isFinite
    val timeoutMilli = if (infinite) 0L else timeout.toMillis

    val start = Instant.now.toEpochMilli

    def elapsedTime(): Long = {
      Instant.now.toEpochMilli - start
    }

    def isTimeoutExpired: Boolean = {
      if (infinite) {
        false
      } else {
        elapsedTime >= timeoutMilli
      }
    }

    def timeLeft(): Long = {
      val timeLeft = timeoutMilli - elapsedTime()
      if (timeLeft < 0L) 0L else timeLeft
    }

    lock.lock()
    try {
      writers += 1
      val isSucceeded = if (maxCapacity == 0) {
        // Synchronous send
        while (syncValue.nonEmpty && !closed && !isTimeoutExpired) {
          if (infinite) {
            cwr.await()
          } else {
            cwr.await(timeLeft(), TimeUnit.MILLISECONDS)
          }
        }
        syncValue match {
          case Some(_) =>
            false
          case None if closed =>
            false
          case None =>
            syncValue = Option(value)
            notifyReaders()
            true
        }
      } else {
        // Asynchronous send
        while (q.size == maxCapacity && !closed && !isTimeoutExpired) {
          if (infinite) {
            cwr.await()
          } else {
            cwr.await(timeLeft(), TimeUnit.MILLISECONDS)
          }
        }

        if (!closed && q.size < maxCapacity) {
          q.enqueue(value)
          notifyReaders()
          true
        } else {
          false
        }
      }
      writers -= 1
      isSucceeded
    } finally {
      lock.unlock()
    }
  }

  override def recv(): T = {
    lock.lock()
    try {
      if (closed) {
        if (syncValue.isEmpty && q.isEmpty) {
          throw new IllegalStateException(s"Attempt to receive from a closed channel.")
        }
      }

      readers += 1

      var v: T = if (maxCapacity == 0) {
        // Synchronous channel
        while (!closed && syncValue.isEmpty) {
          crd.await()
        }
        syncValue.get
      } else {
        // Asynchronous channel
        while (!closed && q.isEmpty) {
          crd.await()
        }
        q.dequeue()
      }
      syncValue = None
      readers -= 1
      cwr.signal()
      v
    } finally {
      lock.unlock()
    }
  }

  override def tryRecv(): Option[T] = {
    lock.lock()
    try {
      if (closed && syncValue.isEmpty && q.isEmpty) {
        None
      } else {
        if (maxCapacity == 0) {
          // Synchronous channel
          if (syncValue.isEmpty) {
            None
          } else {
            val v = syncValue
            syncValue = None
            cwr.signal()
            v
          }
        } else {
          // Asynchronous channel
          if (q.isEmpty) {
            None
          } else {
            val v = q.dequeue()
            cwr.signal()
            Option(v)
          }
        }
      }
    } finally {
      lock.unlock()
    }
  }

  override def tryRecv(timeout: Duration): Option[T] = {
    if (timeout == Duration.Zero) {
      return tryRecv()
    }

    val infinite = !timeout.isFinite
    val timeoutMilli = if (infinite) 0L else timeout.toMillis

    val start = Instant.now.toEpochMilli

    def elapsedTime(): Long = {
      Instant.now.toEpochMilli - start
    }

    def isTimeoutExpired: Boolean = {
      if (infinite) {
        false
      } else {
        elapsedTime >= timeoutMilli
      }
    }

    def timeLeft(): Long = {
      val timeLeft = timeoutMilli - elapsedTime()
      if (timeLeft < 0L) 0L else timeLeft
    }

    lock.lock()
    try {
      readers += 1
      while (!closed && syncValue.isEmpty && q.isEmpty && !isTimeoutExpired) {
        if (infinite) {
          crd.await()
        } else {
          crd.await(timeLeft(), TimeUnit.MILLISECONDS)
        }
      }
      readers -= 1


      fetchValueOpt()
    } finally {
      lock.unlock()
    }

  }

  override def fornew(f: T => Unit): Unit = {
    val valueOpt = tryRecv()
    valueOpt.foreach(v => f(v))
  }

  override def foreach(f: T => Unit): Unit = {
    while (!isClosed) {
      lock.lock()
      readers += 1
      while (!isClosed && syncValue.isEmpty && q.isEmpty) {
        crd.await()
      }
      readers -= 1

      val valOpt = fetchValueOpt()
      lock.unlock()

      valOpt.foreach(f)
    }
  }

  override def isClosed: Boolean = {
    if (syncValue.nonEmpty || q.nonEmpty) {
      false
    } else {
      closed
    }
  }

  override private[channel] def hasMessagesOrClosed: Boolean = {
    lock.lock()
    val ret = closed || syncValue.isDefined || q.nonEmpty
    lock.unlock()
    ret
  }

  override private[channel] def ifEmptyAddWaiter(sem: Semaphore): Boolean = {
    lock.lock()
    try {
      if (closed || syncValue.isDefined || q.nonEmpty) {
        false
      } else {
        waiters.append(sem)
        true
      }
    } finally {
      lock.unlock()
    }
  }

  override private[channel] def delWaiter(sem: Semaphore): Unit = {
    lock.lock()
    try {
      val size1 = waiters.size
      waiters.remove(sem)
      val size2 = waiters.size
      if (size1 != size2 + 1) {
        throw new IllegalStateException(s"Could not find the waiter semaphore.")
      }
    } finally {
      lock.unlock()
    }
  }

  private def notifyReaders(): Unit = {
    if (readers > 0) {
      crd.signal()
    } else {
      if (!waiters.isEmpty) {
        waiters.returnHeadAndRotate().release()
      }
    }
  }

  private def fetchValueOpt(): Option[T] = {
    if (maxCapacity == 0) {
      if (syncValue.nonEmpty) {
        cwr.signal()
      }
      val v = syncValue
      syncValue = None
      v
    } else {
      if (q.isEmpty) {
        None
      } else {
        cwr.signal()
        Option(q.dequeue())
      }
    }
  }
}

object Channel {
  /**
   * Create a synchronous channel.
   *
   * @tparam T The type of the channel.
   * @return A new channel
   */
  def make[T]: Channel[T] = {
    new Channel[T](0)
  }

  /**
   * Create a channel. By default a synchronous channel will be created.
   * If bufferSize is greater then zero, a buffered channel will be created.
   *
   * @param bufferSize Asynchronous buffer size.
   * @tparam T The type of the channel.
   * @return A new channel
   */
  def make[T](bufferSize: Int): Channel[T] = {
    require(bufferSize >= 0)

    new Channel[T](bufferSize)
  }

  /**
   * Waits to receive a message from any of the channels.
   *
   * @param channel  A first channel to wait for (mandatory).
   * @param channels Other channels to wait for.
   * @return A channel that has a pending message.
   */
  def select(channel: ReadChannel[_], channels: ReadChannel[_]*): ChannelLike = {
    trySelect(Duration.Inf, channel, channels: _*).get
  }

  /**
   * Non-blocking check for a pending messages in multiple channels.
   *
   * @param channel  A first channel to wait for (mandatory).
   * @param channels Other channels to wait for.
   * @return A channel that has a pending message or None, if any of the channels have a pending message.
   */
  def trySelect(channel: ReadChannel[_], channels: ReadChannel[_]*): Option[ChannelLike] = {
    trySelect(Duration.Zero, channel, channels: _*)
  }

  /**
   * Waits to receive a message from any of the channels for a specified amount of time.
   *
   * @param timout   A timeout to wait for pending messages.
   * @param channel  A first channel to wait for (mandatory).
   * @param channels Other channels to wait for.
   * @return A channel that has a pending message or None, if any of the channels have a pending message.
   */
  def trySelect(timout: Duration, channel: ReadChannel[_], channels: ReadChannel[_]*): Option[ChannelLike] = {
    val sem = new Semaphore(0)

    // If several channels have pending messages, select randomly the channel to return
    val chans = scala.util.Random.shuffle(channel :: channels.toList).toArray

    // Add waiters
    var i = 0
    while (i < chans.length) {
      val ch = chans(i)
      if (!ch.ifEmptyAddWaiter(sem)) {
        var j = 0
        while (j < i) {
          chans(j).delWaiter(sem)
          j += 1
        }
        return Option(ch)
      }
      i += 1
    }

    while (true) {
      // Re-checking all channels
      i = 0
      while (i < chans.length) {
        val ch = chans(i)
        if (ch.hasMessagesOrClosed) {
          var j = 0
          while (j < chans.length) {
            chans(j).delWaiter(sem)
            j += 1
          }
          return Option(ch)
        }
        i += 1
      }
      val success = if (timout.isFinite) {
        sem.tryAcquire(timout.toMillis, TimeUnit.MILLISECONDS)
      } else {
        sem.acquire()
        true
      }
      if (!success) {
        var j = 0
        while (j < chans.length) {
          chans(j).delWaiter(sem)
          j += 1
        }
        return None
      }
    }
    // This never happens since the method can only exit on other return paths
    null
  }

}
