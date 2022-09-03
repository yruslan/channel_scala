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

trait WriteChannel[-T] extends ChannelLike {
  def send(value: T): Unit
  def trySend(value: T): Boolean
  def trySend(value: T, timeout: Duration): Boolean

  def sender(value: T)(action: => Unit): Selector

  def close(): Unit
}
