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
import java.util.concurrent.TimeUnit

import com.github.yruslan.channel.impl.Awaiter

import scala.collection.mutable
import scala.concurrent.duration.Duration

class AsyncChannel[T](maxCapacity: Int) extends Channel[T] {
  require(maxCapacity > 0)

  protected val q = new mutable.Queue[T]

  override def close(): Unit = {
    lock.lock()
    try {
      if (!closed) {
        closed = true
        readWaiters.foreach(w => w.release())
        writeWaiters.foreach(w => w.release())
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
      while (q.size == maxCapacity && !closed) {
        cwr.await()
      }

      if (!closed) {
        q.enqueue(value)
      }
      notifyReaders()
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

  override def trySend(value: T, timeout: Duration): Boolean = {
    if (timeout == Duration.Zero) {
      return trySend(value)
    }

    val awaiter = new Awaiter(timeout)

    lock.lock()
    try {
      writers += 1

      var isTimeoutExpired = false
      while (!closed && !hasCapacity && !isTimeoutExpired) {
        isTimeoutExpired = !awaiter.await(cwr)
      }

      val isSucceeded = if (!closed && hasCapacity) {
        q.enqueue(value)
        notifyReaders()
        true
      } else {
        false
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
      readers += 1
      while (!closed && q.isEmpty) {
        crd.await()
      }

      if (closed && q.isEmpty) {
        throw new IllegalStateException(s"Attempt to receive from a closed channel.")
      }

      val v: T = q.dequeue()
      readers -= 1

      notifyWriters()
      v
    } finally {
      lock.unlock()
    }
  }

  override def tryRecv(): Option[T] = {
    lock.lock()
    try {
      if (closed && q.isEmpty) {
        None
      } else {
        if (q.isEmpty) {
          None
        } else {
          val v = q.dequeue()
          notifyWriters()
          Option(v)
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

    val awaiter = new Awaiter(timeout)

    lock.lock()
    try {
      readers += 1
      var isTimeoutExpired = false
      while (!closed && !hasMessages && !isTimeoutExpired) {
        isTimeoutExpired = !awaiter.await(crd)
      }
      readers -= 1

      fetchValueOpt()
    } finally {
      lock.unlock()
    }
  }

  override def isClosed: Boolean = {
    if (q.nonEmpty) {
      false
    } else {
      closed
    }
  }

  override protected def hasCapacity: Boolean = {
    q.size < maxCapacity
  }

  override protected def hasMessages: Boolean = {
    q.nonEmpty
  }

  override protected def fetchValueOpt(): Option[T] = {
    if (q.isEmpty) {
      None
    } else {
      notifyWriters()
      Option(q.dequeue())
    }
  }

}
