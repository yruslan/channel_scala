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

import scala.concurrent.duration.Duration

class ChannelDecoratorMap[T, U](inputChannel: ReadChannel[T], f: T => U) extends ChannelDecorator[T](inputChannel) with ReadChannel[U] {
  override def recv(): U = f(inputChannel.recv())

  override def tryRecv(): Option[U] = inputChannel.tryRecv().map(f)

  override def tryRecv(timeout: Duration): Option[U] = inputChannel.tryRecv(timeout).map(f)

  override def recver(action: U => Unit): Selector = inputChannel.recver(t => action(f(t)))

  override def fornew[K](action: U => K): Unit = inputChannel.fornew(t => action(f(t)))

  override def foreach[K](action: U => K): Unit = inputChannel.foreach(t => action(f(t)))
}
