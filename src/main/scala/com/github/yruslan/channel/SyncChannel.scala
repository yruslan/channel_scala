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

import com.github.yruslan.channel.impl.Awaiter

import scala.concurrent.duration.Duration

class SyncChannel[T] extends Channel[T] {
  protected var syncValue: Option[T] = None

  final override def close(): Unit = {
    lock.lock()
    try {
      if (!closed) {
        closed = true
        readWaiters.foreach(w => w.release())
        writeWaiters.foreach(w => w.release())
        crd.signalAll()
        cwr.signalAll()

        writers += 1
        while (syncValue.nonEmpty) {
          cwr.await()
        }
        writers -= 1
      }
    } finally {
      lock.unlock()
    }
  }

  final override def send(value: T): Unit = {
    lock.lock()
    try {
      if (closed) {
        throw new IllegalStateException(s"Attempt to send to a closed channel.")
      }

      writers += 1
      while (syncValue.nonEmpty && !closed) {
        cwr.await()
      }
      if (!closed) {
        syncValue = Option(value)
        notifyReaders()

        while (syncValue.nonEmpty && !closed) {
          cwr.await()
        }
        notifyWriters()
      }
      writers -= 1
    } finally {
      lock.unlock()
    }
  }

  final override def trySend(value: T): Boolean = {
    lock.lock()
    try {
      if (closed) {
        false
      } else {
        if (!hasCapacity) {
          false
        } else {
          syncValue = Option(value)
          notifyReaders()
          true
        }
      }
    } finally {
      lock.unlock()
    }
  }

  final override def trySend(value: T, timeout: Duration): Boolean = {
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

      val isSucceeded = syncValue match {
        case Some(_) =>
          false
        case None if closed =>
          false
        case None if !hasCapacity =>
          false
        case None =>
          syncValue = Option(value)
          notifyReaders()
          true
      }
      writers -= 1
      isSucceeded
    } finally {
      lock.unlock()
    }
  }

  final override def recv(): T = {
    lock.lock()
    try {
      readers += 1
      if (!closed && syncValue.isEmpty) {
        notifyWriters()
      }
      while (!closed && syncValue.isEmpty) {
        crd.await()
      }

      if (closed && syncValue.isEmpty) {
        throw new IllegalStateException(s"Attempt to receive from a closed channel.")
      }

      val v: T = syncValue.get
      syncValue = None
      readers -= 1
      notifyWriters()
      v
    } finally {
      lock.unlock()
    }
  }

  final override def tryRecv(): Option[T] = {
    lock.lock()
    try {
      if (closed && syncValue.isEmpty) {
        None
      } else {
        if (syncValue.isEmpty) {
          None
        } else {
          val v = syncValue
          syncValue = None
          notifyWriters()
          v
        }
      }
    } finally {
      lock.unlock()
    }
  }

  final override def tryRecv(timeout: Duration): Option[T] = {
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

  final override def isClosed: Boolean = {
    lock.lock()
    val result = if (syncValue.nonEmpty) {
      false
    } else {
      closed
    }
    lock.unlock()
    result
  }

  final override protected def hasCapacity: Boolean = {
    syncValue.isEmpty && (readers > 0 || readWaiters.nonEmpty)
  }

  final override protected def hasMessages: Boolean = {
    syncValue.isDefined
  }

  final protected def fetchValueOpt(): Option[T] = {
    if (syncValue.nonEmpty) {
      notifyWriters()
    }
    val v = syncValue
    syncValue = None
    v
  }

}
