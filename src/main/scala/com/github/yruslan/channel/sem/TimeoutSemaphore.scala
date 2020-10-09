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

package com.github.yruslan.channel.sem

import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

import scala.concurrent.duration.Duration

class TimeoutSemaphore(initialValue: Int) {
  private var value = initialValue
  private val lock = new ReentrantLock()
  private val cv = lock.newCondition()

  def acquire(timeout: Duration = Duration.Inf): Boolean = {
    val infinite = !timeout.isFinite()
    val timeoutMilli = if (timeout.isFinite()) timeout.toMillis else 0L

    val start = Instant.now.toEpochMilli

    def isNotTimedOut: Boolean = {
      if (infinite) {
        true
      } else if (timeoutMilli <= 0) {
        false
      } else {
        Instant.now.toEpochMilli - start < timeoutMilli
      }
    }

    def timeLeft(): Long = {
      val timeLeft = timeoutMilli - (Instant.now.toEpochMilli - start)
      if (timeLeft < 0L) 0L else timeLeft
    }

    lock.lock()

    try {
      while (value == 0 && isNotTimedOut) {
        if (infinite) {
          cv.await()
        } else {
          cv.await(timeLeft(), TimeUnit.MILLISECONDS)
        }
      }
      if (value > 0) {
        value -= 1
        true
      } else {
        false
      }
    } finally {
      lock.unlock()
    }
  }

  def release(): Unit = {
    lock.lock()
    try {
      value += 1
      cv.signal()
    } finally {
      lock.unlock()
    }
  }
}
