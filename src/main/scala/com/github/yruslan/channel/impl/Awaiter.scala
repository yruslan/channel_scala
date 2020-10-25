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

package com.github.yruslan.channel.impl

import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition

import scala.concurrent.duration.Duration

class Awaiter(timeout: Duration) {
  private val startInstant = Instant.now()
  private val timeoutMilli = if (timeout.isFinite) timeout.toMillis else 0L

  def await(cond: Condition): Boolean = {
    if (timeout == Duration.Zero) {
      false
    } else {
      if (timeout.isFinite) {
        cond.await(timeLeft(), TimeUnit.MILLISECONDS)
      } else {
        cond.await()
      }
      !isTimeoutExpired
    }
  }

  private def isTimeoutExpired: Boolean = {
    if (!timeout.isFinite) {
      false
    } else {
      elapsedTime >= timeoutMilli
    }
  }

  private def elapsedTime(): Long = {
    val now = Instant.now()
    java.time.Duration.between(startInstant, now).toMillis
  }

  private def timeLeft(): Long = {
    val timeLeft = timeoutMilli - elapsedTime()
    if (timeLeft < 0L) 0L else timeLeft
  }

}
