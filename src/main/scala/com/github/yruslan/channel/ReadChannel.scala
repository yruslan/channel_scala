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

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration

trait ReadChannel[+T] extends ChannelLike {
  @throws[InterruptedException]
  def recv(): T
  def tryRecv(): Option[T]

  @throws[InterruptedException]
  def tryRecv(timeout: Duration): Option[T]

  def recver(action: T => Unit): Selector

  def fornew[U](f: T => U): Unit

  @throws[InterruptedException]
  def foreach[U](f: T => U): Unit

  def map[U](f: T => U): ReadChannel[U] = new ChannelDecoratorMap[T, U](this, f)
  def filter(f: T => Boolean): ReadChannel[T] = new ChannelDecoratorFilter[T](this, f)
  def withFilter(f: T => Boolean): ReadChannel[T] = new ChannelDecoratorFilter[T](this, f)

  def toList: List[T] = {
    val lst = new ListBuffer[T]
    foreach(v => lst += v)
    lst.toList
  }
}
