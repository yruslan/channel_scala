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
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock
import java.util.concurrent.locks.{Condition, Lock, ReentrantLock}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class Channel[T](val capacity: Int) extends ChannelLike {
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
  private val lock = new ReentrantLock()
  private val crd = lock.newCondition()
  private val cwr = lock.newCondition()

  def close(): Unit = {
    lock.lock()
    try {
      if (!closed) {
        closed = true
        for (w <- waiters) {
          w.release()
        }
        crd.notifyAll()
        cwr.notifyAll()
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

      if (capacity == 0) {
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
        while (q.size == capacity && !closed) {
          cwr.await()
        }

        if (!closed) {
          q.enqueue(value)
        }
        notifyReaders()
      }
    } finally {
      lock.unlock()
    }
  }

  def trySend(value: T): Unit = {
    ???
  }

  def recv(): T = {
    ???
  }

  def tryRecv(): Option[T] = {
    ???
  }

  override def isClosed: Boolean = {
    closed
  }

  override def isSame(rhs: ChannelLike): Boolean = {
    this eq rhs
  }

  override protected def getBufSize: Int = {
    lock.lock()
    try {
      if (capacity > 0) {
        q.size
      } else {
        syncValue match {
          case Some(_) => 1
          case None => 0
        }
      }
    } finally {
      lock.unlock()
    }
  }

  override protected def addWaiter(sem: Semaphore): Unit = {
    lock.lock()
    try {
      waiters += sem
    } finally {
      lock.unlock()
    }
  }

  override protected def delWaiter(sem: Semaphore): Unit = {
    lock.lock()
    try {
      waiters --= waiters.filter(_ eq sem)
    } finally {
      lock.unlock()
    }
  }

  private def notifyReaders(): Unit = {
    if (readers > 0){
      crd.signal()
    } else {
      if (waiters.nonEmpty) {
        waiters.head.release()
      }
    }
  }
}
