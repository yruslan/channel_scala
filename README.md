# Channel - a port of GoLang channels to Scala 
[![Build](https://github.com/yruslan/channel_scala/workflows/Build/badge.svg)](https://github.com/yruslan/channel_scala/actions)

> Go channels provide synchronization and messaging, 'select' provides multi-way concurrent control.
> 
> _Rob Pike_ - [Concurrency is not parallelism](https://www.youtube.com/watch?v=oV9rvDllKEg)
/ [another link with better slide view](https://www.youtube.com/watch?v=qmg1CF3gZQ0)

This is one of Scala ports of Go channels. The idea of this particular port is to match as much as possible the features
provided by GoLang so channels can be used for concurrency coordination. The library uses locks, conditional variables and
semaphores as underlying concurrency primitives so the performance is not expected to match applications written in Go.

## Link

|   Scala 2.11   |     Scala 2.12    |  Scala 2.13 |
|:--------------:|:-----------------:|:------------:|
| [![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.yruslan/channel_scala_2.11/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.yruslan/channel_scala_2.12) | [![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.yruslan/channel_scala_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.yruslan/channel_scala_2.12) | [![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.yruslan/channel_scala_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.yruslan/channel_scala_2.13) |

## Motivation
Scala provides channels as a part of the standard library (https://www.scala-lang.org/api/2.13.0/scala/concurrent/Channel.html).
However, the real power of channels comes from `select()`.

Scala promotes Actor model as a way of handling concurrency which is based on works of Carl Hewitt (1973). Channels
are based on CSP model by Tony Hoare (1978). At first glance these models are similar, but they are in fact very different.
Good deescripton on the differences are explained here: https://stackoverflow.com/a/22622880/1038282

CSP channels provide an extremely simple and uniform building block for designing concurrent applications.  

## Usage

Examples for this library are inspired by the [Go By Example](https://gobyexample.com/channels) page by [Mark McGranaghan](https://markmcgranaghan.com/).

You can try these example in Scala REPL.

### Ping
In this example a channel and a thread are created. A message is send from the new thread and received in the main thread.
Since synchronous channels are created by default we can be sure by the time the main thread receives the message,
the child thread is already finished.

> *Note.* Go relies on the builtin operator `<-` for sending and receiving messages. Our implementaion defines `send()` and `recv()` methods
instead to make the code look more Scala-like. Another reason is that in Scala it is not possible to define an infix `<-`.  

```scala
import com.github.yruslan.channel.Channel
import scala.concurrent.Future

// In these examples we always import global execution context
// so that futures could use the default thread pool.
import scala.concurrent.ExecutionContext.Implicits.global

val channel = Channel.make[String]

Future { channel.send("ping") }

val msg = channel.recv()

println(msg)
```

Output:
```
ping
```

### Channel buffering

Channels created by default are synchronous. That means that when a thread sends a message the method won't return
until the message is received by some thread. You may want to make channels asynchronous so that `send()` method 
returns immediately. To do that you need to specify the maximum number of items the channel can store in the buffer.
As long as there is still a free space in the buffer, `send()` will return immediately.

```scala
import com.github.yruslan.channel.Channel

val channel = Channel.make[String](2)

channel.send("buffered")
channel.send("channel")

println(channel.recv())
println(channel.recv())

```
Output:
```
buffered
channel
```

### Channels with unbounded buffer

Some use cases require limited but unknown buffer size in order to satisfy progress guarantees. This port of channels
library supports unbounded channels.

```scala
import com.github.yruslan.channel.Channel

val channel = Channel.makeUnbounded[String]

channel.send("unbounded")
channel.send("buffered")
channel.send("channel")

println(channel.recv())
println(channel.recv())
println(channel.recv())

```
Output:
```
unbounded
buffered
channel
```

### Channel synchronization

Channels can be used for synchronization. Here we use a channel to wait for a job executing in another thread
to complete.

```scala
import com.github.yruslan.channel.Channel
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

def worker(done: Channel[Boolean]): Unit = {
  println("working...")
  Thread.sleep(1000 /*1 second*/)
  println("done")

  done.send(true)
}

val done = Channel.make[Boolean]
Future { worker(done) }

done.recv()
```
Output:
```
working...
done
```

### Directed channels

You can define channel directions. That is channels that can either only send or only receive messages, but not both.

When you define a method, define an argument as `ReadChannel` if you want the method to be able to only receive messages.
Define the argument as `WriteChannel` so the method can only send messages to the channel.

```scala
import com.github.yruslan.channel._

def ping(pings: WriteChannel[String], msg: String): Unit = {
  pings.send(msg)
}

def pong(pings: ReadChannel[String], pongs: WriteChannel[String]): Unit = {
val msg = pings.recv()
  pongs.send(msg)
}

val pings = Channel.make[String](1)
val pongs = Channel.make[String](1)

ping(pings, "message")
pong(pings, pongs)

println(pongs.recv())
```

Output:
```
message
```

### Closing channels
Channels can be closed which prevents sending more messages to it. It can be checked by consumers to determine when
the processing has finished.

```scala
val ch = Channel.make[Int](5)

ch.send(1)
ch.send(2)
ch.send(3)
ch.close()

while (!ch.isClosed) {
  println(ch.recv())
}
```

Output:
```
1
2
3
```

### Iterate over channels
You can iterate over a channel using `foreach()`. Several threads can do the same. Each thread will receive only one
copy of the sent message. Be careful, `foreach()` is blocking and will exit only when the channel is closed. If you
forget to close a channel, the foreach loop will block the thread.

Here is an example how a stream of tasks can be processed in parallel by 2 workers using `foreach()`.   

```scala
Future {
  ch.foreach(v => {
    println(s"Worker 1 received $v")
    Thread.sleep(500) // Simulate processing
  })
}

Future {
  ch.foreach(v => {
    println(s"Worker 2 received $v")
    Thread.sleep(600) // Simulate processing
  })
}

ch.send(1)
ch.send(2)
ch.send(3)
ch.send(4)
ch.close()
```

Output:
```
Worker 1 received 1
Worker 2 received 2
Worker 1 received 3
Worker 2 received 4
```

### Select

What makes channels great is that a program can wait for events in several channels at the same time.
In this example `select()` is used to wait for any of two channels to have an incoming message. Once a message
is available it is received.

```scala
import com.github.yruslan.channel.Channel
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

val channel1 = Channel.make[String]
val channel2 = Channel.make[String]

Future {
  Thread.sleep(1000 /*1 second*/)
  channel1.send("one")
}

Future {
  Thread.sleep(2000 /*2 seconds*/)
  channel2.send("two")
}

for (_ <- Range(0, 2)) {
  Channel.select(
      channel1.recver{v => println(v)},
      channel2.recver{v => println(v)}
  )
}
```
Output:
```
one
two
```

#### Default blocks
Like in Go, you can have default blocks that will be executed if none of other blocks are ready:

```scala
Channel.select(
    channel1.recver{v => println(v)},
    channel2.sender(v) { /* An action to do after the send. */ },
    Channel.default { /* The action to do if nether channel1 nor channel2 can receive and send. */ }
)
```

#### Time.after()
In Go, you can use `Time.After(duration)` to generate a channel that sends a message after the specified time has
passed. You don't need to close the channel afterward. The Scala implementation is `TimeChannels.after(duration)` 

```scala
import com.github.yruslan.channel._

Channel.select(
  someChannel.recver { v => println(s"Got a message in time: $v") },
  TimeChannels.after(Duration(10, TimeUnit.SECONDS)).recver { v => println("Time is out!") }
)
```

#### Time.newTicker()
In Go, you can use `Time.newTicker(duration)` to generate a channel that sends a message after the specified time.
When the message is consumed, the ticker will generate another message after the same time duration.
Tickers should be closed after they are not in use. The Scala implementation is `TimeChannels.ticker(duration)`

```scala
import com.github.yruslan.channel._

val ticker = TimeChannels.ticker(Duration(10, TimeUnit.SECONDS))

Channel.select(
  someChannel.recver { v => println(s"Got a message: $v") },
  ticker.recver { _ => println("Tick.") }
)

ticker.close()
```

### Non-blocking methods
Go supports non-blocking channel operation by the elegant `default` clause in the `select` statement. The scala port
adds separate methods that support non-blocking operations: `trySend()`, `tryRecv()` and `trySelect()`. There is an
optional timeout parameter for each of these methods. If it is not specified, all methods return immediately without
any waiting. If the timeout is specified the methods will wait the specified amount of time if the expected conditions
are not met. Timeout can be set to `Duration.Inf`. In this case these methods are equivalent to their blocking variants
with the exception of the type of returned value.

* `trySend()` returns a boolean. If `true`, the message has been sent successfully, otherwise it is failed for whatever
   reason (maybe the channel was closed or the buffer is full).
* `tryRecv()` returns a optional value. If there are no pending messages the method returns `None`.
* `trySelect()` returns true if any of specified operations have executed.

Here is an example of non-blocking methods:
```scala
val ch1 = Channel.make[Int](1)
val ch2 = Channel.make[String](1)

var ok = ch1.trySend(1)
println(s"msg1 -> channel1: $ok")

ok = ch1.trySend(2)
println(s"msg2 -> channel1: $ok")

val msg1 = ch1.tryRecv()
println(s"msg1 <- channel1: $msg1")

val msg2 = ch1.tryRecv()
println(s"msg2 <- channel1: $msg2")

val okSelect = Channel.trySelect(
  ch1.recver{v => println(v)},
  ch2.recver{v => println(v)})
println(s"selected: $okSelect")
```

Output:
```
msg1 -> channel1: true
msg2 -> channel1: false
msg1 <- channel1: Some(1)
msg2 <- channel1: false
selected: None
```

### General pattern for select()
Since channels can be copied back and forth netween threads, each channel can have multiple readers and writers. So
if `sclect()` returns a channel there are no guarantees that another thread won't fetch the message before the current
thread can receive it. 

Here is an example where the worker is written so it would work correctly in case channels have multiple readers and
writers.

```scala
import com.github.yruslan.channel.Channel
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import Channel._

def worker(channel1: Channel[Int], channel2: Channel[String]): Unit = {
  while(!channel1.isClosed && !channel2.isClosed) {
    select(
      channel1.recver{i => println(s"Int received: $i")},
      channel2.recver{s => println(s"String received: $s")}
    )
  }
}

val channell = Channel.make[Int]
val channel2 = Channel.make[String]

val fut = Future {
  worker(channell, channel2)
}

channell.send(1)
channel2.send("abc")
channell.send(2)
channel2.send("edef")
channell.close()
channel2.close()
```

Output:
```
Int received: 1
String received: abc
Int received: 2
String received: edef
```

### A balancer example
Here is example function that balances inputs from two channels into two output channels.
Notice that `select()` is used to wait for any of the channels to have an incoming message, as well as
to select a free output channel. You can mix `sender()` and `recver()` calls in the same `select()` statement.
A finish channel is used to signal the end of the balancing process.

```scala
def balancer(input1: ReadChannel[Int],
             input2: ReadChannel[Int],
             output1: WriteChannel[Int],
             output2: WriteChannel[Int],
             finishChannel: ReadChannel[Boolean]): Unit = {
  var v: Int = 0
  var exit = false

  while (!exit) {
    select(
      input1.recver(x => v = x),
      input2.recver(x => v = x),
      finishChannel.recver(_ => exit = true)
    )

    if (!exit) {
      select(
        output1.sender(v) {},
        output2.sender(v) {}
      )
    }
  }
}
```

### Scala-specific channel features
Since Scala is a functional language, this implementation of channels supports functional operations used in for comprehension.

#### map()

You can lazy map messages in a channel.

```scala
// Creating a channel of integers
val chInt = Channel.make[Int](2)

// The channel of strings is the result of mapping the channel of integer 
val chString = chInt.map(v => v.toString)

// Send some integers
chInt.send(1)
chInt.send(2)
chInt.close()

// Receive some strings
val s1: String = chString.recv()
val s2: String = chString.recv()
```

#### filter()

You can lazy filer messages in a channel.

```scala
// Creating a channel of integers
val chInput = Channel.make[Int](3)

// Filter the original channel 
val chFiltered = chInput.filter(v => v != 2)

// Send some integers
chInput.send(1)
chInput.send(2)
chInput.send(3)
chInput.close()

// Receive filtered values
val v1 = chFiltered.recv() // 1
val v2 = chFiltered.recv() // 3
```

#### for() comprehension

You can use `for` comprehension for channels.

```scala
// Creating a channel of integers
val chInput = Channel.make[Int](3)

// Applying maps and filters
val chOutput = chInput
  .map(v => v * 2)
  .filter(v => v != 4)

// Sending values to the input channel
chInput.send(1)
chInput.send(2)
chInput.send(3)
chInput.close()

// Traversing the output channel using for comprehension
for {
  a <- chOutput
  if a > 5
} println(a)

// Outputs:
// 6
```

#### toList

You can convert a channel to `List` by collecting all messages. This operation will block until the channel is closed.

```scala
val chInput = Channel.make[Int](3)

chInput.send(1)
chInput.send(2)
chInput.send(3)
chInput.close()

val lst = chInput.toList // List(1, 2, 3)
```

## Changelog
- #### 0.2.0 released Feb 19, 2024.
    - Add support for `default` block in `select()`.
    - Add `after()` and `ticker()`.
    - Change select() logic for synchronous channels to match behavior of GoLang.

- #### 0.1.6 released Dec 3, 2023.
    - Fix channel filtering does not filter some values at random.

- #### 0.1.5 released Nov 26, 2023.
    - Add handling of `InterruptedException` that can occur while waiting on a channel.
    - Add support for priority `prioritySelect()` for channels. When several selectors are ready the first one will take precedence.
    - Fix race condition in `foreach()` when an exception is thrown inside the action.

- #### 0.1.4 released Sep 8, 2022.
    - Add covariance for read-only channels.
      - E.g. `val ch1: ReadChannel[Animal] = ch2: ReadChannel[Dog]`.
    - Add contravariance for write-only channels.
      - E.g. `val ch1: Writehannel[Dog] = ch2: Writehannel[Animal]`.

- #### 0.1.3 released Mar 13, 2022.
   - Add support for unbounded channels (use `Channel.makeUnbounded[MyType]()`.

- #### 0.1.2 released Jan 13, 2022.
   - Add support for `map()`, `filter()` and `for` comprehension for channels.

- #### 0.1.1 released May 11, 2021.
   - Fix one corner case of trySelect().

- #### 0.1.0 released Feb 17, 2021.
   - The initial release.
