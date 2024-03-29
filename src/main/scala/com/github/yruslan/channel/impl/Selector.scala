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

import com.github.yruslan.channel.Channel

private[channel] abstract class Selector(val isSender: Boolean,
                                         val isDefault: Boolean,
                                         val channel: Channel[_]) {
  def sendRecv(Opt: Option[Waiter]): Int
  def afterAction(): Unit
}
