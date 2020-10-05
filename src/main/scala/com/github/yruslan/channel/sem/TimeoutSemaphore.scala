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

import scala.concurrent.duration.Duration

class TimeoutSemaphore(initialValue: Int) {
  private var value = initialValue

  def acquire(timeout: Duration = Duration.Inf): Boolean = this.synchronized {
    val timeoutMilli = if (timeout.isFinite()) timeout.toMillis else 0L
    val start = Instant.now.toEpochMilli

    def isNotTimedOut: Boolean = {
      if (timeoutMilli == 0L) {
        true
      } else {
        Instant.now.toEpochMilli - start > timeoutMilli
      }
    }

    while (value == 0 && isNotTimedOut) {
      val nanos = if (timeout.isFinite()) timeout.toNanos else 0L
      wait(nanos)
    }
    if (value > 0) {
      value -= 1
      true
    } else {
      false
    }
  }

  def release(): Unit = this.synchronized {
    value += 1
    this.notify()
  }
}
