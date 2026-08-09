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

import com.github.yruslan.channel.exception.NegativeWaitGroupCounter

import java.lang.Thread.UncaughtExceptionHandler

object TestUtils {
  def createThread(action: => Unit): Thread = {
    // Creating thread in the Scala 2.11 compatible way.
    // Please do not remove 'new Runnable'
    new Thread(new Runnable {
      override def run(): Unit = action
    })
  }

  def setUncaughtExceptionHandler(thread: Thread)(handler: (Thread, Throwable) => Unit): Unit = {
    // Creating thread in the Scala 2.11 compatible way.
    // Please do not remove 'new UncaughtExceptionHandler'
    thread.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
      def uncaughtException(t: Thread, e: Throwable): Unit = handler(t, e)
    })
  }
}
