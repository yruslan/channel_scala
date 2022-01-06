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

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration

trait ReadChannel[T] extends ChannelLike {
  def recv(): T
  def tryRecv(): Option[T]
  def tryRecv(timeout: Duration): Option[T]

  def recver(action: T => Unit): Selector

  def fornew[U](f: T => U): Unit
  def foreach[U](f: T => U): Unit

  def map[U](f: T => U): ReadChannel[U] = new ChannelDecoratorMap[T, U](this, f)
  def filter(f: T => Boolean): ReadChannel[T] = new ChannelDecoratorFilter[T](this, f)

  def toList: List[T] = {
    val lst = new ListBuffer[T]
    foreach(v => lst += v)
    lst.toList
  }
}
