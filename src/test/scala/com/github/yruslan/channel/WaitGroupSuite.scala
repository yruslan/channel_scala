package com.github.yruslan.channel

import com.github.yruslan.channel.exception.NegativeWaitGroupCounter
import org.scalatest.wordspec.AnyWordSpec
import TestUtils._

class WaitGroupSuite extends AnyWordSpec {
  "WaitGroup" should {
    "wait for all done" in {
      val wg = WaitGroup()
      val n = 10
      for (_ <- 1 to n) {
        wg.add()
        val k = createThread {
          Thread.sleep(1000)
          wg.done()
        }
        k.start()
      }
      wg.await()
    }

    "done()" should {
      "throw an exception if the counter is negative" in {
        val wg = WaitGroup()
        wg.add()
        wg.done()
        assertThrows[NegativeWaitGroupCounter] {
          wg.done()
        }
      }
    }

    "wait()" should {
      "throw an exception if the counter is negative" in {
        val wg = WaitGroup()
        wg.add(2)

        val t = createThread {
          Thread.sleep(1000)
          wg.add(-3)
        }

        setUncaughtExceptionHandler(t) { (_, e) =>
          assert(e.isInstanceOf[NegativeWaitGroupCounter])
        }

        t.start()
        assertThrows[NegativeWaitGroupCounter] {
          wg.await()
        }
        t.join()
      }
    }
  }
}
