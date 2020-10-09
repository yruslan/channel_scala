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

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration

class Channel[T](val maxCapacity: Int) extends ReadChannel[T] with WriteChannel[T] {
  private var readers: Int = 0
  private var writers: Int = 0
  private var closed = false
  private val q = new mutable.Queue[T]
  private val waiters = new ListBuffer[Semaphore]
  private var syncValue: Option[T] = None

  // Scala & Java monitors are designed so each object can act as a mutex and a condition variable.
  // But this makes impossible to use a single lock for more than one condition.
  // So a lock from [java.util.concurrent.locks] is used instead. It allows to have several condition
  // variables that use a single lock.
  override val lock = new ReentrantLock()
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
        throw new IllegalStateException(s"Attempt to send to a closed channel.")
      }
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
    } finally {
      lock.unlock()
    }
  }

  override def trySend(value: T, timeout: Duration = Duration.Zero): Boolean = {
    if (timeout == Duration.Zero) {
      return trySend(value)
    }

    val infinite = !timeout.isFinite()
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

  override def tryRecv(timeout: Duration = Duration.Zero): Option[T] = {
    if (timeout == Duration.Zero) {
      return tryRecv()
    }

    val infinite = !timeout.isFinite()
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

      val v: Option[T] = if (maxCapacity == 0) {
        // Synchronous channel
        while (!closed && syncValue.isEmpty && !isTimeoutExpired) {
          if (infinite) {
            crd.await()
          } else {
            crd.await(timeLeft(), TimeUnit.MILLISECONDS)
          }
        }
        syncValue
      } else {
        // Asynchronous channel
        while (!closed && q.isEmpty && !isTimeoutExpired) {
          if (infinite) {
            crd.await()
          } else {
            crd.await(timeLeft(), TimeUnit.MILLISECONDS)
          }
        }
        if (q.isEmpty) {
          None
        } else {
          Option(q.dequeue())
        }
      }

      readers -= 1

      if (v.isDefined) {
        syncValue = None
        cwr.signal()
      }
      v
    } finally {
      lock.unlock()
    }

  }

  override def foreach(f: T => Unit): Unit = {
    var hasData = true

    while (hasData) {
      val valueOpt = tryRecv()

      valueOpt.foreach(v => f(v))

      lock.lock()
      hasData = syncValue.isDefined || q.nonEmpty
      lock.unlock()
    }
  }

  override def isClosed: Boolean = {
    if (syncValue.nonEmpty || q.nonEmpty) {
      false
    } else {
      closed
    }
  }

  override private[channel] def getBufSize: Int = {
    if (maxCapacity > 0) {
      q.size
    } else {
      syncValue match {
        case Some(_) => 1
        case None => 0
      }
    }
  }

  override private[channel] def addWaiter(sem: Semaphore): Unit = {
    lock.lock()
    try {
      waiters += sem
    } finally {
      lock.unlock()
    }
  }

  override private[channel] def delWaiter(sem: Semaphore): Unit = {
    lock.lock()
    try {
      val size1 = waiters.size
      waiters --= waiters.filter(_ eq sem)
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
      if (waiters.nonEmpty) {
        waiters.head.release()
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
   * Waits to receive a message from any of the channels for a specified amout of time.
   *
   * @param channel  A first channel to wait for (mandatory).
   * @param channels Other channels to wait for.
   * @return A channel that has a pending message or None, if any of the channels have a pending message.
   */
  def trySelect(timout: Duration, channel: ReadChannel[_], channels: ReadChannel[_]*): Option[ChannelLike] = {
    val sem = new Semaphore(0)

    val chans = (channel :: channels.toList).toArray

    // Add waiters
    var i = 0
    while (i < chans.length) {
      val ch = chans(i)
      ch.lock.lock()
      try {
        if (ch.getBufSize > 0 || ch.isClosed) {
          var j = 0
          while (j < i) {
            chans(j).delWaiter(sem)
            j += 1
          }
          return Option(ch)
        }
        ch.addWaiter(sem)
      } finally {
        ch.lock.unlock()
      }
      i += 1
    }

    while (true) {
      // Re-checking all channels
      i = 0
      while (i < chans.length) {
        val ch = chans(i)
        val lock = ch.lock
        lock.lock()
        try {
          if (ch.getBufSize > 0 || ch.isClosed) {
            var j = 0
            while (j < chans.length) {
              chans(j).delWaiter(sem)
              j += 1
            }
            return Option(ch)
          }
        } finally {
          lock.unlock()
        }
        i += 1
      }
      val success = if (timout.isFinite()) {
        sem.tryAcquire(timout.toMillis, TimeUnit.MILLISECONDS)
      } else {
        sem.acquire()
        true
      }
      if (!success) {
        return None
      }
    }
    // This never happens since the method can only exit on other return paths
    null
  }

}
