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

import com.github.yruslan.channel.impl.Waiter

abstract class ChannelDecorator[T](inputChannel: ReadChannel[T]) extends ChannelLike {
  override def isClosed: Boolean = inputChannel.isClosed

  override private[channel] def hasMessagesStatus: Int = inputChannel.hasMessagesStatus

  override private [channel] def hasFreeCapacityStatus: Int = inputChannel.hasFreeCapacityStatus

  override private [channel] def ifEmptyAddReaderWaiter(waiter: Waiter): Boolean = inputChannel.ifEmptyAddReaderWaiter(waiter)

  override private [channel] def ifFullAddWriterWaiter(waiter: Waiter): Boolean = inputChannel.ifFullAddWriterWaiter(waiter)

  override private [channel] def delReaderWaiter(waiter: Waiter): Unit = inputChannel.delReaderWaiter(waiter)

  override private [channel] def delWriterWaiter(waiter: Waiter): Unit = inputChannel.delWriterWaiter(waiter)
}
