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

import java.util.concurrent.locks.ReentrantLock

import com.github.yruslan.channel.sem.TimeoutSemaphore

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration

class Channel[T](val maxCapacity: Int) extends ChannelLike {
  private var readers: Int = 0
  private var writers: Int = 0
  private var closed = false
  private val q = new mutable.Queue[T]
  private val waiters = new ListBuffer[TimeoutSemaphore]
  private var syncValue: Option[T] = None

  // Scala & Java monitors are designed so each object can act as a mutex and a condition variable.
  // But this makes impossible to use a single lock for more than one condition.
  // So a lock from [java.util.concurrent.locks] is used instead. It allows to have several condition
  // variables that use a single lock.
  override private[channel] val lock = new ReentrantLock()
  private val crd = lock.newCondition()
  private val cwr = lock.newCondition()

  def close(): Unit = {
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

  def send(value: T): Unit = {
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

  def trySend(value: T): Boolean = {
    lock.lock()
    try {
      if (closed) {
        throw new IllegalStateException(s"Attempt to send to a closed channel.")
      }
      if (maxCapacity == 0) {
        throw new IllegalStateException(s"trySend cannot be used on a sync channel.")
      }
      if (q.size == maxCapacity) {
        false
      } else {
        q.enqueue(value)
        notifyReaders()
        true
      }
    } finally {
      lock.unlock()
    }
  }

  def recv(): T = {
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
      if (readers == 0) {
        cwr.signal()
      }
      v
    } finally {
      lock.unlock()
    }
  }

  def tryRecv(): Option[T] = {
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

  override def isClosed: Boolean = {
    closed
  }

  override def isSame(rhs: ChannelLike): Boolean = {
    this eq rhs
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

  override private[channel] def addWaiter(sem: TimeoutSemaphore): Unit = {
    lock.lock()
    try {
      waiters += sem
    } finally {
      lock.unlock()
    }
  }

  override private[channel] def delWaiter(sem: TimeoutSemaphore): Unit = {
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

  def createSyncChannel[T](): Channel[T] = new Channel[T](0)

  def createAsyncChannel[T](maxCapacity: Int): Channel[T] = new Channel[T](maxCapacity)

  /**
   * Waits to receive a message from any of the channels.
   *
   * @param channel  A first channel to wait for (mandatory).
   * @param channels Other channels to wait for.
   * @return A channel that has a pending message.
   */
  def select(channel: ChannelLike, channels: ChannelLike*): ChannelLike = {
    trySelect(Duration.Inf, channel, channels: _*).get
  }

  /**
   * Waits to receive a message from any of the channels for a specified amout of time.
   *
   * @param channel  A first channel to wait for (mandatory).
   * @param channels Other channels to wait for.
   * @return A channel that has a pending message or None, if any of the channels have a pending message.
   */
  def trySelect(timout: Duration, channel: ChannelLike, channels: ChannelLike*): Option[ChannelLike] = {
    val sem = new TimeoutSemaphore(0)

    var i = 0

    val chans = (channel :: channels.toList).toArray

    // Add waiters
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

    i = 0

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
      val success = sem.acquire(timout)
      if (!success) {
        return None
      }
    }
    // This never happens since the method can only exit on other return paths
    null
  }

}
