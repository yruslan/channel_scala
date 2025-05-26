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

import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

class TimeChannelsSuite extends AnyWordSpec {
  "ticker" should {
    "generate a ticker that ticks until closed" in {
      val start = Instant.now()

      val ticker = TimeChannels.ticker(Duration(10, TimeUnit.MILLISECONDS))

      ticker.recv()
      val middle = Instant.now()
      ticker.recv()
      val finish = Instant.now()
      ticker.close()

      assert(java.time.Duration.between(start, middle).toMillis >= 10L)
      assert(java.time.Duration.between(start, middle).toMillis <= 500L)
      assert(java.time.Duration.between(middle, finish).toMillis >= 10L)
      assert(java.time.Duration.between(middle, finish).toMillis <= 500L)
    }

    "generate a ticker that ticks until closed111" in {
      val ticker = TimeChannels.ticker(Duration(10, TimeUnit.MILLISECONDS))

      val start = Instant.now()
      ticker.close()
      val finish = Instant.now()

      assert(java.time.Duration.between(start, finish).toMillis < 10L)
    }
  }

  "after" should {
    "generate a single shot read channel" in {
      val start = Instant.now()

      val after = TimeChannels.after(Duration(10, TimeUnit.MILLISECONDS))

      after.recv()
      val finish = Instant.now()

      assert(java.time.Duration.between(start, finish).toMillis >= 10L)
      assert(after.isClosed)
    }
  }
}
