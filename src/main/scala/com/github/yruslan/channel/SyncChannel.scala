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

import scala.concurrent.duration.Duration

class SyncChannel[T] extends Channel[T] {
  protected var syncValue: Option[T] = None
  protected var sender: Long = -1

  @throws[InterruptedException]
  final override def close(): Unit = {
    lock.lock()
    try {
      if (!closed) {
        //println(s"${Thread.currentThread().getId} Closing...")
        closed = true
        readWaiters.foreach(w => w.sem.release())
        writeWaiters.foreach(w => w.sem.release())
        crd.signalAll()
        cwr.signalAll()

        writers += 1
        while (syncValue.nonEmpty) {
          awaitWriters()
        }
        writers -= 1
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
      while (hasMessages && !closed) {
        awaitWriters()
      }
      if (!closed) {
        //println(s"${Thread.currentThread().getId} Sent: ${value}")
        syncValue = Option(value)
        sender = Thread.currentThread().getId
        notifySyncReaders()

        while (syncValue.nonEmpty && !closed) {
          awaitWriters()
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
          //println(s"${Thread.currentThread().getId} Sent: ${value}")
          syncValue = Option(value)
          sender = Thread.currentThread().getId
          notifySyncReaders()
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

      val isSucceeded = syncValue match {
        case Some(_) =>
          false
        case None if closed =>
          false
        case None if !hasCapacity =>
          false
        case None =>
          syncValue = Option(value)
          sender = Thread.currentThread().getId
          notifySyncReaders()
          true
      }
      writers -= 1
      isSucceeded
    } finally {
      lock.unlock()
    }
  }

  @throws[InterruptedException]
  final override def recv(): T = {
    lock.lock()
    try {
      readers += 1
      if (!closed && syncValue.isEmpty) {
        notifyWriters()
      }
      while (!closed && syncValue.isEmpty) {
        awaitReaders()
      }

      if (closed && syncValue.isEmpty) {
        throw new IllegalStateException(s"Attempt to receive from a closed channel.")
      }

      val v: T = syncValue.get
      syncValue = None
      sender = -1
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
        } else if (sender == Thread.currentThread().getId) {
          notifySyncReaders()
          None
        } else {
          val v = syncValue
          //println(s"${Thread.currentThread().getId} Received: ${v}")
          syncValue = None
          sender = -1
          notifyWriters()
          v
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
    lock.lock()
    val result = if (syncValue.nonEmpty) {
      false
    } else {
      closed
    }
    lock.unlock()
    result
  }

  /* This method assumes the lock is being held. */
  final override protected def hasCapacity: Boolean = {
    if (syncValue.isEmpty && (readers > 0)) {
      true
    } else if (syncValue.isDefined) {
      false
    } else {
      val myThreadId = Thread.currentThread().getId

      var foundOtherThread = false
      readWaiters.foreach(waiter => if (waiter.threadId != myThreadId) foundOtherThread = true)

      foundOtherThread
    }
  }

  /* This method assumes the lock is being held. */
  final override protected def hasMessages: Boolean = {
    syncValue.isDefined && Thread.currentThread().getId != sender
  }

  /* This method assumes the lock is being held. */
  final protected def fetchValueOpt(): Option[T] = {
    if (syncValue.nonEmpty) {
      notifyWriters()
    }
    val v = syncValue
    syncValue = None
    sender = -1
    v
  }

  /* This method assumes the lock is being held. */
  final private def notifySyncReaders(): Unit = {
    if (readers > 0) {
      crd.signal()
    } else {
      if (!readWaiters.isEmpty) {
        val count = readWaiters.size
        //println(s"${Thread.currentThread().getId} Readers count: ${count}")
        var waiter = readWaiters.returnHeadAndRotate()
        var i = 0
        while (i < count && waiter.threadId == sender) {
          i += 1
          if (waiter.threadId == sender) {
            //println(s"${Thread.currentThread().getId} Skipping waking up ${waiter.threadId}...${count}")
          }
          waiter = readWaiters.returnHeadAndRotate()

        }

        if (waiter.threadId != sender) {
          //println(s"${Thread.currentThread().getId} Waking up ${waiter.threadId}...")
          waiter.sem.release()
        }
      }
    }
  }
}
