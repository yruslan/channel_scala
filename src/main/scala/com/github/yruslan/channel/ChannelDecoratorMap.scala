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

import scala.concurrent.duration.Duration

class ChannelDecoratorMap[T, U](inputChannel: ReadChannel[T], f: T => U) extends ChannelDecorator[T](inputChannel) with ReadChannel[U] {
  @throws[InterruptedException]
  override def recv(): U = f(inputChannel.recv())

  override def tryRecv(): Option[U] = inputChannel.tryRecv().map(f)

  @throws[InterruptedException]
  override def tryRecv(timeout: Duration): Option[U] = inputChannel.tryRecv(timeout).map(f)

  override def recver(action: U => Unit): Selector = inputChannel.recver(t => action(f(t)))

  override def fornew[K](action: U => K): Unit = inputChannel.fornew(t => action(f(t)))

  @throws[InterruptedException]
  override def foreach[K](action: U => K): Unit = inputChannel.foreach(t => action(f(t)))
}
