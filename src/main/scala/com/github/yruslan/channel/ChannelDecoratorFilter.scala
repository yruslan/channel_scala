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

  override def fornew(action: T => Unit): Unit = inputChannel.fornew(t => if (pred(t)) action(t))

  override def foreach(action: T => Unit): Unit = inputChannel.foreach(t => if (pred(t)) action(t))
}
