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

import com.github.yruslan.channel.exception.NegativeWaitGroupCounter

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
  * WaitGroup is a synchronization primitive ported from GoLang allows one or more threads to wait until a set of
  * operations completes.
  */
class WaitGroup {
  private val lock = new ReentrantLock()
  private val cond = lock.newCondition()
  private val counter: AtomicInteger = new AtomicInteger(0)

  /**
    * Add adds delta, which may be negative, to the WaitGroup counter. If the counter becomes zero, all waiters
    * wake up and can continue.
    *
    * If the counter goes negative, the `NegativeWaitGroupCounter` exception is thrown at the current thread
    * and all waiters.
    */
  @throws[NegativeWaitGroupCounter]
  def add(delta: Int = 1): Unit = {
    val after = counter.addAndGet(delta)

    if (after <= 0) {
      lock.lock()
      try {
        cond.signalAll()
        if (after < 0)
          throw new NegativeWaitGroupCounter
      } finally {
        lock.unlock()
      }
    }
  }

  /**
    * Done decrements the WaitGroup counter by one.
    *
    * If the counter goes negative, the `NegativeWaitGroupCounter` exception is thrown at the current thread
    * and all waiters.
    */
  @throws[NegativeWaitGroupCounter]
  def done(): Unit = {
    add(-1)
  }

  /**
    * Await blocks until the WaitGroup counter is zero.
    *
    * In GoLang the corresponding method is `wait()`. However, in Java `wait()` is a method of `Object` and is used
    * for implementation of monitors, and cannot be overridden. Therefore, this method is named `await()` instead.
    *
    * If the counter goes negative, the `NegativeWaitGroupCounter` exception is thrown.
    */
  @throws[InterruptedException]
  @throws[NegativeWaitGroupCounter]
  def await(): Unit = {
    lock.lock()
    try {
      while (counter.get() > 0) {
        cond.await()
      }
      if (counter.get() < 0) {
        throw new NegativeWaitGroupCounter
      }
    } finally {
      lock.unlock()
    }
  }
}

object WaitGroup {
  def apply(): WaitGroup = new WaitGroup
}
