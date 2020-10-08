# Channel - a port of GoLang channels to Scala 

This is one of Scala ports of GoLang channels. The idea of this particular port is to match as much as possible the features
provided by GoLang so channels can be used for concurrency coordination. The library uses locks, conditional variables and
semaphores as underlying concurrency primitives so the performance is not expected to match applications written in Go.

## Link

*The library hasn't been released yet.*

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
    case c if c == channel1 => println(channel1.recv())
    case c if c == channel2 => println(channel2.recv())
  }
}
```
Output:
```
one
two
```

## Reference

*ToDo*
