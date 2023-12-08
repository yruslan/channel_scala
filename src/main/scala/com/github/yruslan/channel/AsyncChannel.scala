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

import com.github.yruslan.channel.impl.Awaiter

import scala.collection.mutable
import scala.concurrent.duration.Duration

class AsyncChannel[T](maxCapacity: Int) extends Channel[T] {
  require(maxCapacity > 0)

  protected val q = new mutable.Queue[T]

  final override def close(): Unit = {
    lock.lock()
    try {
      if (!closed) {
        closed = true
        readWaiters.foreach(w => w.sem.release())
        writeWaiters.foreach(w => w.sem.release())
        crd.signalAll()
        cwr.signalAll()
      }
    } finally {
      lock.unlock()
    }
  }

  @throws[InterruptedException]
  final override def send(value: T): Unit = {
    lock.lock()
    try {
      if (closed) {
        throw new IllegalStateException(s"Attempt to send to a closed channel.")
      }

      writers += 1
      while (q.size == maxCapacity && !closed) {
        awaitWriters()
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

  final override def trySend(value: T): Boolean = {
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

  @throws[InterruptedException]
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
        isTimeoutExpired = !awaitWriters(awaiter)
      }
      writers -= 1

      if (!closed && hasCapacity) {
        q.enqueue(value)
        notifyReaders()
        true
      } else {
        false
      }
    } finally {
      lock.unlock()
    }
  }

  @throws[InterruptedException]
  final override def recv(): T = {
    lock.lock()
    try {
      readers += 1
      while (!closed && q.isEmpty) {
        awaitReaders()
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

  final override def tryRecv(): Option[T] = {
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

  @throws[InterruptedException]
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
        isTimeoutExpired = !awaitReaders(awaiter)
      }
      readers -= 1

      fetchValueOpt()
    } finally {
      lock.unlock()
    }
  }

  final override def isClosed: Boolean = {
    if (q.nonEmpty) {
      false
    } else {
      closed
    }
  }

  /* This method assumes the lock is being held. */
  final override protected def hasCapacity: Boolean = {
    q.size < maxCapacity
  }

  /* This method assumes the lock is being held. */
  final override protected def hasMessages: Boolean = {
    q.nonEmpty
  }

  /* This method assumes the lock is being held. */
  final override protected def fetchValueOpt(): Option[T] = {
    if (q.isEmpty) {
      None
    } else {
      notifyWriters()
      Option(q.dequeue())
    }
  }
}
