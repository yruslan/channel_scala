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

import java.util.concurrent.locks.{Condition, ReentrantLock}
import java.util.concurrent.{Semaphore, TimeUnit}
import com.github.yruslan.channel.impl.{Awaiter, ChannelImpl, Selector, SimpleLinkedList, Waiter}

import scala.concurrent.duration.Duration

abstract class Channel[T] extends ChannelImpl with ReadChannel[T] with WriteChannel[T] {
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

  final override private[channel] def ifEmptyAddReaderWaiter(waiter: Waiter): Boolean = {
    lock.lock()
    try {
      if (closed) {
        false
      } else if (hasMessages) {
        readWaiters.append(waiter)
        false
      } else {
        readWaiters.append(waiter)
        true
      }
    } finally {
      lock.unlock()
    }
  }

  final override private[channel] def ifFullAddWriterWaiter(waiter: Waiter): Boolean = {
    lock.lock()
    try {
      if (closed) {
        false
      } else if (hasCapacity) {
        writeWaiters.append(waiter)
        false
      } else {
        writeWaiters.append(waiter)
        true
      }
    } finally {
      lock.unlock()
    }
  }

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
    val status = if (closed) {
      Channel.CLOSED
    } else if (hasCapacity) {
      Channel.AVAILABLE
    } else {
      Channel.NOT_AVAILABLE
    }
    lock.unlock()
    status
  }

  final override private[channel] def delReaderWaiter(waiter: Waiter): Unit = {
    lock.lock()
    try {
      readWaiters.remove(waiter)
    } finally {
      lock.unlock()
    }
  }

  final override private[channel] def delWriterWaiter(waiter: Waiter): Unit = {
    lock.lock()
    try {
      writeWaiters.remove(waiter)
    } finally {
      lock.unlock()
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
    * @return true if one of pending operations wasn't blockingю
    */
  @throws[InterruptedException]
  def trySelect(timout: Duration, isPriorityOrdered: Boolean, selector: Selector, selectors: Selector*): Boolean = {
    val waiter = new Waiter(new Semaphore(0), Thread.currentThread().getId)

    val sel = if (isPriorityOrdered) {
      // If channels are ordered by priority, retain the original order
      (selector :: selectors.toList).toArray
    } else {
      // If several channels have pending messages, select randomly the channel to return
      scala.util.Random.shuffle(selector :: selectors.toList).toArray
    }

    // Add waiters
    var i = 0
    while (i < sel.length) {
      val s = sel(i)
      val blocking = if (s.isSender) {
        s.channel.ifFullAddWriterWaiter(waiter)
      } else {
        s.channel.ifEmptyAddReaderWaiter(waiter)
      }
      if (!blocking && s.sendRecv()) {
        removeWaiters(waiter, sel, i + 1)
        s.afterAction()
        return true
      }
      i += 1
    }

    while (true) {
      // Re-checking all channels
      i = 0
      while (i < sel.length) {
        //println(s"${Thread.currentThread().getId} Checking...")
        val s = sel(i)
        val status = if (s.isSender)
          s.channel.hasFreeCapacityStatus
        else
          s.channel.hasMessagesStatus

        if (status == AVAILABLE && s.sendRecv()) {
          removeWaiters(waiter, sel, sel.length)
          s.afterAction()
          return true
        } else if (status == CLOSED) {
          //println(s"${Thread.currentThread().getId} Got closed...")
          return false
        }
        i += 1
      }

      val success = try {
        if (timout.isFinite) {
          waiter.sem.tryAcquire(timout.toMillis, TimeUnit.MILLISECONDS)
        } else {
          //println(s"${Thread.currentThread().getId} Waiting...")
          waiter.sem.acquire()
          //println(s"${Thread.currentThread().getId} Awake...")
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
}
