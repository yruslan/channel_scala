package com.github.yruslan.channel

import com.github.yruslan.channel.exception.NegativeWaitGroupCounter
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import TestUtils._
import java.util.concurrent.atomic.AtomicInteger

class WaitGroupSuite extends AnyWordSpec with Eventually {
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))
  "WaitGroup" should {
    "wait for all done" in {
      val wg = WaitGroup()
      val n = 10
      val counter = new AtomicInteger(0)
      for (_ <- 1 to n) {
        wg.add()
        createThread {
          counter.incrementAndGet()
          wg.done()
        }.start()
      }
      wg.await()
      eventually {
        assert(counter.get() == n)
      }
    }

    "done()" should {
      "throw an exception if the counter is negative" in {
        val wg = WaitGroup()
        wg.add()
        wg.done()
        eventually {
          assertThrows[NegativeWaitGroupCounter] {
            wg.done()
          }
        }
      }
    }

    "wait()" should {
      "throw an exception if the counter is negative" in {
        val wg = WaitGroup()
        wg.add(2)

        @volatile var exceptionCaught = false
        val t = createThread {
          wg.add(-3)
        }

        setUncaughtExceptionHandler(t) { (_, e) =>
          exceptionCaught = true
          assert(e.isInstanceOf[NegativeWaitGroupCounter])
        }

        t.start()
        assertThrows[NegativeWaitGroupCounter] {
          wg.await()
        }
        eventually {
          assert(exceptionCaught)
        }
      }
    }

    "support multiple add and done calls" in {
      val wg = WaitGroup()
      val counter = new AtomicInteger(0)
      wg.add(5)
      for (_ <- 1 to 5) {
        createThread {
          counter.incrementAndGet()
          wg.done()
        }.start()
      }
      wg.await()
      eventually {
        assert(counter.get() == 5)
      }
    }

    "be reusable after await completes" in {
      val wg = WaitGroup()
      val counter1 = new AtomicInteger(0)
      val counter2 = new AtomicInteger(0)

      wg.add(3)
      for (_ <- 1 to 3) {
        createThread {
          counter1.incrementAndGet()
          wg.done()
        }.start()
      }
      wg.await()
      eventually {
        assert(counter1.get() == 3)
      }

      wg.add(2)
      for (_ <- 1 to 2) {
        createThread {
          counter2.incrementAndGet()
          wg.done()
        }.start()
      }
      wg.await()
      eventually {
        assert(counter2.get() == 2)
      }
    }

    "handle concurrent add operations correctly" in {
      val wg = WaitGroup()
      val n = 20
      val counter = new AtomicInteger(0)

      for (_ <- 1 to n) {
        createThread {
          wg.add()
          counter.incrementAndGet()
          wg.done()
        }.start()
      }

      eventually {
        wg.await()
        assert(counter.get() == n)
      }
    }
  }
}
