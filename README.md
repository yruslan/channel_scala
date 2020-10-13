# Channel - a port of GoLang channels to Scala 

> Go channels provide synchronization and messaging, 'select' provides multi-way concurrent control.
> 
> _Rob Pike_ - [Concurrency is not parallelism](https://www.youtube.com/watch?v=oV9rvDllKEg)
/ [another link with better slide view](https://www.youtube.com/watch?v=qmg1CF3gZQ0)

This is one of Scala ports of Go channels. The idea of this particular port is to match as much as possible the features
provided by GoLang so channels can be used for concurrency coordination. The library uses locks, conditional variables and
semaphores as underlying concurrency primitives so the performance is not expected to match applications written in Go.

## Link

*The library hasn't been released yet.*

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
  Channel.select(channel1, channel2) match {
    case `channel1` => println(channel1.recv())
    case `channel2` => println(channel2.recv())
  }
}
```
Output:
```
one
two
```

### Non-blocking methods
Go supports non-blocking channel operation by the elegant `default` clause in the `select` statement. The scala port
adds separate methods that support non-blocking operations: `trySend()`, `tryRecv` and `trySelect`. There is an
optional timeout parameter for each of these methods. If it is not specified, all methods return immediately without
any waiting. If the timeout is specified the methods will wait the specified amount of time if the expected conditions
are not met. Timeout can be set to `Duration.Inf`. In this case these methods are equivalent to their blocking variants
with the exception of the type of returned value.

* `trySend()` returns a boolean. If `true`, the message has been sent successfully, otherwise it is failed for whatever
   reason (maybe the channel was closed or the buffer is full).
* `tryRecs()` returns a optional value. If there are no pending messages the method returns `None`.
* `trySelect()` returns a optional channel. If there are no pending messages the method returns `None`.

Here is an example of non-blocking methods:
```scala
val ch1 = Channel.make[Int]
val ch2 = Channel.make[String](1)

var ok = ch1.trySend(1)
println(s"msg1 -> channel1: $ok")

ok = ch1.trySend(2)
println(s"msg2 -> channel1: $ok")

val msg1 = ch1.tryRecv()
println(s"msg1 <- channel1: $msg1")

val msg2 = ch1.tryRecv()
println(s"msg2 <- channel1: $msg2")

val s = Channel.trySelect(ch1, ch2)
println(s"selected: $s")
```

Output:
```
msg1 -> channel1: true
msg2 -> channel1: false
msg1 <- channel1: Some(1)
msg2 <- channel1: None
selected: None
```

### General pattern for select()
Since channels can be copied back and forth netween threads, each channel can have multiple readers and writers. So
if `sclect()` returns a channel there are no guarantees that another thread won't fetch the message before the current
thread can receive it. 

Here is an example where the worker is written so it would work correctly in case channels have multiple readers and
writers. After a channel is selected we need to make sure to receive the message only if it still available there.
So `tryRecv()` is used to do the non-blocking check and fetch.

```scala
import com.github.yruslan.channel.Channel
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import Channel._

def worker(channel1: Channel[Int], channel2: Channel[String]): Unit = {
  while(!channel1.isClosed && !channel2.isClosed) {
    select(channel1, channel2) match {
      case `channel1` =>
        val msgOpt = channel1.tryRecv()
        msgOpt.foreach(i => println(s"Int received: $i"))
      case `channel2` =>
        val msgOpt = channel2.tryRecv()
        msgOpt.foreach(s => println(s"String received: $s"))
    }
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

The boilerplate code can be simplified using `fornew()` method which invokes a lambda function for each new message
received from the channel.
 
```scala
def worker(channel1: Channel[Int], channel2: Channel[String]): Unit = {
  while(!channel1.isClosed && !channel2.isClosed) {
    select(channel1, channel2) match {
      case `channel1` => channel1.fornew( i => println(s"Int received: $i"))
      case `channel2` => channel2.fornew( s => println(s"String received: $s"))          }
  }
}
```

## Reference

*ToDo*
