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

import com.github.yruslan.channel.Channel.{CLOSED, SUCCESS, WAITING_REQUIRED}
import com.github.yruslan.channel.impl.{Awaiter, Selector, SimpleLinkedList, Waiter}

import java.util.concurrent.locks.{Condition, ReentrantLock}
import java.util.concurrent.{Semaphore, TimeUnit}
import scala.concurrent.duration.Duration
import scala.util.Random

abstract class Channel[T] extends ReadChannel[T] with WriteChannel[T] {
  protected var readers: Int = 0
  protected var writers: Int = 0
  protected var closed = false

  protected val readWaiters = new SimpleLinkedList[Waiter]
  protected val writeWaiters = new SimpleLinkedList[Waiter]

  // Scala & Java monitors are designed so each object can act as a mutex and a condition variable.
  // But this makes impossible to use a single lock for more than one condition.
  // So a lock from [java.util.concurrent.locks] is used instead. It allows to have several condition
  // variables that use a single lock.
  protected val lock = new ReentrantLock()
  protected val crd: Condition = lock.newCondition()
  protected val cwr: Condition = lock.newCondition()

  final override def fornew[U](f: T => U): Unit = {
    var valueOpt = tryRecv()

    while (valueOpt.nonEmpty) {
      valueOpt.foreach(v => f(v))
      valueOpt = tryRecv()
    }
  }

  @throws[InterruptedException]
  final override def foreach[U](f: T => U): Unit = {
    while (true) {
      var valOpt: Option[T] = None

      lock.lock()
      try {
        readers += 1
        while (!closed && !hasMessages) {
          awaitReaders()
        }
        readers -= 1
        if (isClosed) {
          return
        }

        valOpt = fetchValueOpt()
      } finally {
        lock.unlock()
      }

      valOpt.foreach(f)
    }
  }

  protected def fetchValueOpt(): Option[T]

  final override def sender(value: T)(action: => Unit = {}): Selector = {
    new Selector(true, false, this) {
      override def sendRecv(waiterOpt: Option[Waiter]): Int = {
        lock.lock()
        try {
          if (closed) {
            CLOSED
          } else {
            val ok = trySend(value)
            if (ok) {
              SUCCESS
            } else {
              waiterOpt.foreach(waiter => writeWaiters.append(waiter))
              WAITING_REQUIRED
            }
          }
        } finally {
          lock.unlock()
        }
      }

      override def afterAction(): Unit = action
    }
  }

  final override def recver(action: T => Unit): Selector = {
    new Selector(false, false, this) {
      var el: T = _

      override def sendRecv(waiterOpt: Option[Waiter]): Int = {
        lock.lock()
        try {
          val opt = tryRecv()
          opt.foreach(v => el = v)
          if (opt.isEmpty) {
            if (closed) {
              CLOSED
            } else {
              waiterOpt.foreach(waiter => readWaiters.append(waiter))
              WAITING_REQUIRED
            }
          } else {
            SUCCESS
          }
        } finally {
          lock.unlock()
        }
      }

      override def afterAction(): Unit = action(el)
    }
  }

  /* This method assumes the lock is being held. */
  final protected def notifyReaders(): Unit = {
    if (readers > 0) {
      crd.signal()
    } else {
      if (!readWaiters.isEmpty) {
        readWaiters.returnHeadAndRotate().sem.release()
      }
    }
  }

  /* This method assumes the lock is being held. */
  final protected def notifyWriters(): Unit = {
    if (writers > 0) {
      cwr.signal()
    } else {
      if (!writeWaiters.isEmpty) {
        writeWaiters.returnHeadAndRotate().sem.release()
      }
    }
  }

  /* This method assumes the lock is being held. */
  protected def hasCapacity: Boolean

  /* This method assumes the lock is being held. */
  protected def hasMessages: Boolean

  /* This method assumes the lock is being held. */
  @throws[InterruptedException]
  final protected def awaitWriters(): Unit = {
    try {
      cwr.await()
    } catch {
      case ex: Throwable =>
        writers -= 1
        cwr.signal()
        throw ex
    }
  }

  /* This method assumes the lock is being held. */
  @throws[InterruptedException]
  final protected def awaitWriters(awaiter: Awaiter): Boolean = {
    try {
      awaiter.await(cwr)
    } catch {
      case ex: Throwable =>
        writers -= 1
        cwr.signal()
        throw ex
    }
  }

  /* This method assumes the lock is being held. */
  @throws[InterruptedException]
  final protected def awaitReaders(): Unit = {
    try {
      crd.await()
    } catch {
      case ex: Throwable =>
        readers -= 1
        crd.signal()
        throw ex
    }
  }

  /* This method assumes the lock is being held. */
  @throws[InterruptedException]
  final protected def awaitReaders(awaiter: Awaiter): Boolean = {
    try {
      awaiter.await(crd)
    } catch {
      case ex: Throwable =>
        readers -= 1
        crd.signal()
        throw ex
    }
  }

  @inline
  final private def delReaderWaiter(waiter: Waiter): Unit = {
    lock.lock()
    try {
      readWaiters.remove(waiter)
    } finally {
      lock.unlock()
    }
  }

  @inline
  final private def delWriterWaiter(waiter: Waiter): Unit = {
    lock.lock()
    try {
      writeWaiters.remove(waiter)
    } finally {
      lock.unlock()
    }
  }
}

object Channel {
  val SUCCESS = 0
  val WAITING_REQUIRED = 1
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
    * Create an unbounded asynchronous channel.
    *
    * @tparam T The type of the channel.
    * @return A new channel
    */
  def makeUnbounded[T]: Channel[T] = {
    new AsyncChannel[T](Int.MaxValue)
  }

  /**
    * Waits for a non-blocking operation to be available on the list of channels.
    * If more than one channel is ready to perform its operation, the channel to perform the operation on will be chosen
    * at random.
    *
    * @param selector  A first channel to wait for (mandatory).
    * @param selectors Other channels to wait for.
    * @return true is none of the channels are closed and select() can be invoked again, false if at least one of channels is closed.
    */
  def select(selector: Selector, selectors: Selector*): Boolean = {
    trySelect(Duration.Inf, false, selector, selectors: _*)
  }

  /**
    * Waits for a non-blocking operation to be available on the list of channels.
    * If more than one channel is ready to perform its operation, the first one in the list takes precedence.
    *
    * @param selector  A first channel to wait for (mandatory).
    * @param selectors Other channels to wait for.
    * @return true is none of the channels are closed and select() can be invoked again, false if at least one of channels is closed.
    */
  def prioritySelect(selector: Selector, selectors: Selector*): Boolean = {
    trySelect(Duration.Inf, true, selector, selectors: _*)
  }

  /**
    * Non-blocking check for a possibility of a non-blocking operation on several channels.
    * If more than one channel is ready to perform its operation, the channel to perform the operation on will be chosen
    * at random.
    *
    * @param selector  A first channel to wait for (mandatory).
    * @param selectors Other channels to wait for.
    * @return true if one of pending operations wasn't blocking.
    */
  def trySelect(selector: Selector, selectors: Selector*): Boolean = {
    trySelect(Duration.Zero, false, selector, selectors: _*)
  }

  /**
    * Non-blocking check for a possibility of a non-blocking operation on several channels.
    *
    * @param selector  A first channel to wait for (mandatory).
    * @param selectors Other channels to wait for.
    * @return true if one of pending operations wasn't blocking.
    */
  def trySelect(timout: Duration, selector: Selector, selectors: Selector*): Boolean = {
    trySelect(timout, false, selector, selectors: _*)
  }

  /**
    * Non-blocking check for a possibility of a non-blocking operation on several channels.
    * If more than one channel is ready to perform its operation, the first one in the list takes precedence.
    *
    * @param selector  A first channel to wait for (mandatory).
    * @param selectors Other channels to wait for.
    * @return true if one of pending operations wasn't blocking.
    */
  def tryPrioritySelect(selector: Selector, selectors: Selector*): Boolean = {
    trySelect(Duration.Zero, true, selector, selectors: _*)
  }

  /**
    * Waits for a non-bloaking action to be available.
    *
    * @param timout            A timeout to wait for a non-blocking action to be available.
    * @param isPriorityOrdered If true, when more then one selectors is ready, the first one in the list will be selected.
    * @param selector          A first channel to wait for (mandatory).
    * @param selectors         Other channels to wait for.
    * @return true if one of pending operations wasn't blocking.
    */
  @throws[InterruptedException]
  final def trySelect(timout: Duration, isPriorityOrdered: Boolean, selector: Selector, selectors: Selector*): Boolean = {
    val sel = (selector +: selectors).toArray

    if (!isPriorityOrdered) {
      shuffleArray(sel)
    }

    if (ifHasDefaultProcessSelectors(sel))
      return true

    val waiter = new Waiter(new Semaphore(0), Thread.currentThread().getId)

    // Add waiters
    var i = 0
    while (i < sel.length) {
      val s = sel(i)
      val status = s.sendRecv(Some(waiter))
      if (status == SUCCESS) {
        removeWaiters(waiter, sel, i)
        s.afterAction()
        return true
      }
      i += 1
    }

    while (true) {
      // Re-checking all channels
      i = 0
      while (i < sel.length) {
        val s = sel(i)
        val status = s.sendRecv(None)

        if (status == SUCCESS) {
          removeWaiters(waiter, sel, sel.length)
          s.afterAction()
          return true
        } else if (status == CLOSED) {
          removeWaiters(waiter, sel, sel.length)
          return false
        }
        i += 1
      }

      val success = try {
        if (timout.isFinite) {
          waiter.sem.tryAcquire(timout.toMillis, TimeUnit.MILLISECONDS)
        } else {
          waiter.sem.acquire()
          true
        }
      } catch {
        case ex: Throwable =>
          removeWaiters(waiter, sel, sel.length)
          throw ex
      }

      if (!success) {
        removeWaiters(waiter, sel, sel.length)
        return false
      }
    }
    // This never happens since the method can only exit on other return paths
    false
  }

  final def default(action: => Unit): Selector = {
    new Selector(false, true, null) {
      override def sendRecv(waiterOpt: Option[Waiter]): Int = {
        SUCCESS
      }

      override def afterAction(): Unit = action
    }
  }

  final private def ifHasDefaultProcessSelectors(selectors: Array[Selector]): Boolean = {
    var i = 0
    var defaults = 0
    var defaultSelectorIndex = -1
    while (i < selectors.length) {
      if (selectors(i).isDefault) {
        defaultSelectorIndex = i
        defaults += 1
      }
      i += 1
    }

    if (defaults == 1) {
      selectWithDefault(selectors, defaultSelectorIndex)
      true
    } else if (defaults > 1) {
      throw new IllegalArgumentException("Only one default selector is allowed.")
    } else {
      false
    }
  }

  /**
    * Activates one of selectors if available, executes the default selector if no other selectors are available.
    *
    * @param selectors         Channel selectors to wait for.
    */
  @throws[InterruptedException]
  final private def selectWithDefault(selectors: Array[Selector], defaultSelectorIndex: Int): Unit = {
    var i = 0
    while (i < selectors.length) {
      val s = selectors(i)
      if (!s.isDefault) {
        val status = s.sendRecv(None)
        if (status == SUCCESS) {
          s.afterAction()
          return
        }
      }
      i += 1
    }

    selectors(defaultSelectorIndex).afterAction()
  }


  @inline
  final private def removeWaiters(waiter: Waiter, sel: Array[Selector], numberOfWaiters: Int): Unit = {
    var j = 0
    while (j < numberOfWaiters) {
      if (sel(j).isSender) {
        sel(j).channel.delWriterWaiter(waiter)
      } else {
        sel(j).channel.delReaderWaiter(waiter)
      }
      j += 1
    }
  }

  final private def shuffleArray(array: Array[Selector]): Unit = {
    val random = new Random()
    var i = array.length - 1
    while (i > 0) {
      val j = random.nextInt(i + 1)
      val temp = array(i)
      array(i) = array(j)
      array(j) = temp
      i -= 1
    }
  }
}
