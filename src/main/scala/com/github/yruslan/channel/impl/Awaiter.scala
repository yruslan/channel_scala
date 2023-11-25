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

package com.github.yruslan.channel.impl

import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition

import scala.concurrent.duration.Duration

class Awaiter(timeout: Duration) {
  private val startInstant = Instant.now()
  private val timeoutMilli = if (timeout.isFinite) timeout.toMillis else 0L

  @throws[InterruptedException]
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
