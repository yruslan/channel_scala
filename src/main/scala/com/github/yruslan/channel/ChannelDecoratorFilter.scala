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

import com.github.yruslan.channel.impl.Selector

import java.time.Instant
import java.time.Instant.now
import scala.concurrent.duration.{Duration, MILLISECONDS}

class ChannelDecoratorFilter[T](inputChannel: ReadChannel[T], pred: T => Boolean) extends ChannelDecorator[T](inputChannel) with ReadChannel[T] {
  override def recv(): T = {
    var v = inputChannel.recv()
    var found = pred(v)

    while (!found) {
      v = inputChannel.recv()
      found = pred(v)
    }
    v
  }

  override def tryRecv(): Option[T] = {
    var valueOpt = inputChannel.tryRecv()
    var found = valueOpt.isEmpty || valueOpt.forall(v => pred(v))

    while (!found) {
      valueOpt = inputChannel.tryRecv()
      found = valueOpt.isEmpty || valueOpt.forall(v => pred(v))
    }
    valueOpt
  }

  override def tryRecv(timeout: Duration): Option[T] = {
    if (timeout == Duration.Zero) {
      return tryRecv()
    }

    val timeoutMilli = if (timeout.isFinite) timeout.toMillis else 0L
    val startInstant = Instant.now()
    var valueOpt = inputChannel.tryRecv(timeout)
    var found = valueOpt.isEmpty || valueOpt.forall(v => pred(v))
    var elapsedTime = java.time.Duration.between(startInstant, now).toMillis

    if (found || elapsedTime >= timeoutMilli) {
      valueOpt
    } else {
      while (!found && elapsedTime < timeoutMilli) {
        val newTimeout = Duration(timeoutMilli - elapsedTime, MILLISECONDS)
        valueOpt = inputChannel.tryRecv(newTimeout)
        found = valueOpt.isEmpty || valueOpt.forall(v => pred(v))
        elapsedTime = java.time.Duration.between(startInstant, now).toMillis
      }
      valueOpt
    }
  }

  override def recver(action: T => Unit): Selector = inputChannel.recver(t => if (pred(t)) action(t))

  override def fornew[U](action: T => U): Unit = inputChannel.fornew(t => if (pred(t)) action(t))

  override def foreach[U](action: T => U): Unit = inputChannel.foreach(t => if (pred(t)) action(t))
}
