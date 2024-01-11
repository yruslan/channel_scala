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

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration

/**
  * The object contains channel generators similar to GoLang: https://gobyexample.com/tickers
  */
object TimeChannels {
  /**
    * Create a channel that gets a single message after the specified duration, and then closes.
    *
    * Example:
    * {{{
    *   val after = TimeChannels.after(Duration(10, TimeUnit.MILLISECONDS))
    *   after.recv()
    *   // 10 ms has passed.
    *   // You don't need to close the channel
    * }}}
    */
  def after(duration: Duration)(implicit executor: ExecutionContext): ReadChannel[Instant] = {
    val channel = Channel.make[Instant](1)
    Future {
      Thread.sleep(duration.toMillis)
      channel.send(Instant.now())
      channel.close()
    }

    channel
  }

  /**
    * Create a ticker channel, see https://gobyexample.com/tickers
    *
    * {{{
    *   val ticker = TimeChannels.ticker(Duration(10, TimeUnit.MILLISECONDS))
    *   val instant = ticker.recv()
    *   ticker.close()
    * }}}
    *
    */
  def ticker(duration: Duration)(implicit executor: ExecutionContext): Channel[Instant] = {
    val channel = Channel.make[Instant]
    Future {
      while (!channel.isClosed) {
        Thread.sleep(duration.toMillis)
        channel.send(Instant.now())
      }
    }

    channel
  }
}
