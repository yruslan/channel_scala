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

import java.util.concurrent.locks.{Condition, ReentrantLock}
import java.util.concurrent.{Semaphore, TimeUnit}

import com.github.yruslan.channel.impl.{Selector, SimpleLinkedList}

import scala.concurrent.duration.Duration

abstract class Channel[T] extends ReadChannel[T] with WriteChannel[T] {
  protected var readers: Int = 0
  protected var writers: Int = 0
  protected var closed = false

  protected val readWaiters = new SimpleLinkedList[Semaphore]
  protected val writeWaiters = new SimpleLinkedList[Semaphore]

  // Scala & Java monitors are designed so each object can act as a mutex and a condition variable.
  // But this makes impossible to use a single lock for more than one condition.
  // So a lock from [java.util.concurrent.locks] is used instead. It allows to have several condition
  // variables that use a single lock.
  protected val lock = new ReentrantLock()
  protected val crd: Condition = lock.newCondition()
  protected val cwr: Condition = lock.newCondition()

  final override private[channel] def ifEmptyAddReaderWaiter(sem: Semaphore): Boolean = {
    lock.lock()
    try {
      if (closed || hasMessages) {
        false
      } else {
        readWaiters.append(sem)
        true
      }
    } finally {
      lock.unlock()
    }
  }

  final override private[channel] def ifFullAddWriterWaiter(sem: Semaphore): Boolean = {
    lock.lock()
    try {
      if (closed || hasCapacity) {
        false
      } else {
        writeWaiters.append(sem)
        true
      }
    } finally {
      lock.unlock()
    }
  }

  final override def fornew(f: T => Unit): Unit = {
    val valueOpt = tryRecv()
    valueOpt.foreach(v => f(v))
  }

  final override def foreach(f: T => Unit): Unit = {
    while (true) {
      lock.lock()
      readers += 1
      while (!closed && !hasMessages) {
        crd.await()
      }
      readers -= 1
      if (isClosed) {
        lock.unlock()
        return
      }

      val valOpt = fetchValueOpt()
      lock.unlock()

      valOpt.foreach(f)
    }
  }

  protected def fetchValueOpt(): Option[T]

  final override def sender(value: T) (action: => Unit = {}): Selector = {
    new Selector(true, this) {
      override def sendRecv(): Boolean = trySend(value)

      override def afterAction(): Unit = action
    }
  }

  final override def recver(action: T => Unit): Selector = {
    new Selector(false, this) {
      var el: T = _

      override def sendRecv(): Boolean = {
        val opt = tryRecv()
        opt.foreach(v => el = v)
        opt.isDefined
      }

      override def afterAction(): Unit = action(el)
    }
  }

  final override private[channel] def hasMessagesStatus: Int = {
    lock.lock()
    val status = if (hasMessages) {
      Channel.AVAILABLE
    } else if (closed) {
      Channel.CLOSED
    } else {
      Channel.NOT_AVAILABLE
    }
    lock.unlock()
    status
  }

  final override private[channel] def hasFreeCapacityStatus: Int = {
    lock.lock()
    val status = if (hasCapacity) {
      Channel.AVAILABLE
    } else if (closed) {
      Channel.CLOSED
    } else {
      Channel.NOT_AVAILABLE
    }
    lock.unlock()
    status
  }

  final override private[channel] def delReaderWaiter(sem: Semaphore): Unit = {
    lock.lock()
    try {
      readWaiters.remove(sem)
    } finally {
      lock.unlock()
    }
  }

  final override private[channel] def delWriterWaiter(sem: Semaphore): Unit = {
    lock.lock()
    try {
      writeWaiters.remove(sem)
    } finally {
      lock.unlock()
    }
  }

  final protected def notifyReaders(): Unit = {
    if (readers > 0) {
      crd.signal()
    } else {
      if (!readWaiters.isEmpty) {
        readWaiters.returnHeadAndRotate().release()
      }
    }
  }

  final protected def notifyWriters(): Unit = {
    if (writers > 0) {
      cwr.signal()
    } else {
      if (!writeWaiters.isEmpty) {
        writeWaiters.returnHeadAndRotate().release()
      }
    }
  }

  protected def hasCapacity: Boolean

  protected def hasMessages: Boolean
}

object Channel {
  val NOT_AVAILABLE = 0
  val AVAILABLE = 1
  val CLOSED = 2

  /**
   * Create a synchronous channel.
   *
   * @tparam T The type of the channel.
   * @return A new channel
   */
  def make[T]: Channel[T] = {
    new SyncChannel[T]
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

    if (bufferSize > 0) {
      new AsyncChannel[T](bufferSize)
    } else {
      new SyncChannel[T]
    }
  }

  /**
   * Waits for a non-blocking operation to be available on the list of channels.
   *
   * @param selector  A first channel to wait for (mandatory).
   * @param selectors Other channels to wait for.
   * @return true is none of the channels are closed and select() can be invoked again, false if at least one of channels is closed.
   */
  def select(selector: Selector, selectors: Selector*): Boolean = {
    trySelect(Duration.Inf, selector, selectors: _*)
  }

  /**
   * Non-blocking check for a possibility of a non-blocking operation on several channels.
   *
   * @param selector  A first channel to wait for (mandatory).
   * @param selectors Other channels to wait for.
   * @return true if one of pending operations wasn't blocking.
   */
  def trySelect(selector: Selector, selectors: Selector*): Boolean = {
    trySelect(Duration.Zero, selector, selectors: _*)
  }

  /**
   * Waits for a non-bloaking action to be available.
   *
   * @param timout    A timeout to wait for a non-blocking action to be available.
   * @param selector  A first channel to wait for (mandatory).
   * @param selectors Other channels to wait for.
   * @return true if one of pending operations wasn't blockingю
   */
  def trySelect(timout: Duration, selector: Selector, selectors: Selector*): Boolean = {
    val sem = new Semaphore(0)

    // If several channels have pending messages, select randomly the channel to return
    val sel = scala.util.Random.shuffle(selector :: selectors.toList).toArray

    // Add waiters
    var i = 0
    while (i < sel.length) {
      val s = sel(i)
      val blocking = if (s.isSender) {
        s.channel.ifFullAddWriterWaiter(sem)
      } else {
        s.channel.ifEmptyAddReaderWaiter(sem)
      }
      if (!blocking && s.sendRecv()) {
        s.afterAction()
        var j = 0
        while (j < i) {
          if (sel(j).isSender) {
            sel(j).channel.delWriterWaiter(sem)
          } else {
            sel(j).channel.delReaderWaiter(sem)
          }
          j += 1
        }
        return true
      }
      i += 1
    }

    while (true) {
      // Re-checking all channels
      i = 0
      while (i < sel.length) {
        val s = sel(i)
        if (s.isSender) {
          val status = s.channel.hasFreeCapacityStatus
          if (status == AVAILABLE && s.sendRecv()) {
            s.afterAction()
            var j = 0
            while (j < sel.length) {
              sel(j).channel.delWriterWaiter(sem)
              j += 1
            }
            return true
          } else if (status == CLOSED) {
            return false
          }
        } else {
          val status = s.channel.hasMessagesStatus
          if (status == AVAILABLE && s.sendRecv()) {
            s.afterAction()
            var j = 0
            while (j < sel.length) {
              sel(j).channel.delReaderWaiter(sem)
              j += 1
            }
            return true
          } else if (status == CLOSED) {
            return false
          }
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
        while (j < sel.length) {
          if (sel(j).isSender) {
            sel(j).channel.delWriterWaiter(sem)
          } else {
            sel(j).channel.delReaderWaiter(sem)
          }
          j += 1
        }
        return false
      }
    }
    // This never happens since the method can only exit on other return paths
    false
  }

}
