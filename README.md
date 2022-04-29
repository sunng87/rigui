# Ri Gui （日晷, Sundial）

[![CI](https://github.com/sunng87/rigui/actions/workflows/clojure.yml/badge.svg)](https://github.com/sunng87/rigui/actions/workflows/clojure.yml)
[![Clojars](https://img.shields.io/clojars/v/rigui.svg)](https://clojars.org/rigui)
[![GitHub license](https://img.shields.io/github/license/sunng87/rigui.svg)](https://github.com/sunng87/rigui/blob/master/LICENSE)

Hierarchical Timing Wheels for Clojure and ClojureScript. This is a
general purpose timer implementation that scales. Its performance can
be tuned via parameters.

Timing wheels is designed for scenario of large scale task
scheduling. According to current [benchmark](#performance-comparison)
it provides way better accuracy for large amount of tasks emitted in
short range.

![rigui](https://upload.wikimedia.org/wikipedia/commons/thumb/3/35/Beijing_sundial.jpg/318px-Beijing_sundial.jpg)

## Base Usage

Start a timer:

```clojure
(require '[rigui.core :refer [start later! every! stop cancel!]])

;; starting a timer with arguments:
;; tick size: 1ms
;; bucket per wheel: 8
;; handler function: some-handler
;; thread pool for running tasks: some-executor (for JVM only)
(def timer (start 1 8 some-handler some-executor))
```

Schedule some task/value for later:

```clojure
;; schedule some value :a with delay 1000ms
;; timer's handler function will be called with :a in 1000ms

(def task (later! timer :a 1000))
```

Note that values to scheduled within a tick will be delivered to
handler function at once, and executed on current thread.

The handler function will be called with task value as arguments when
the task expires. The run a custom task, you can start the timer as:

```clojure
(def timer (start 1 8 (fn [f] (f)) some-executor))

(later! timer #(println :a) 1000)
```

The schedule function returns a `promise`-like object, which maintains
the execution result of handler function and task value. `deref`,
blocking `deref` and `realized?` are supported.

Cancel a task:

```clojure
(cancel! timer task)
```

Schedule some task/value for a fixed interval. The value will be
delivered to handler function in fixed interval.

```clojure
(every! timer :a
  1000 ;; initial delay
  500 ;; interval
  )
```

Stop the timer:

```clojure
;; calling stop on timer returns all the pending tasks
(stop timer)
```

Once a timer is stopped, it no longer accepts new task.

## Delayed Channel

This library also provides an experimental core.async channel that pop
the value after some delay.

```clojure
(require '[rigui.async :refer [delayed-chan delayed-value]])

(def c (delayed-chan))

;; the value will be available in 1000 milliseconds
(>!! c (delayed-value :a 1000))

;; blocked until 1000 milliseconds later
(<!! c)
```

## Performance comparison

In the `bench/` source directory, I made a bench script to compare
performance of Rigui and `ScheduleThreadPoolExecutor`. With the
`(thoughput 50000)`, I try to schedule 50000 tasks that will be
emitted within 5 seconds. This function will prints its result to stdout:

```
Testing enqueue time for JVM timer and Rigui
Enqueue 50000 tasks into JVM timer
"Elapsed time: 89.618297 msecs"

Enqueue 50000 tasks into Rigui timer
"Elapsed time: 591.834465 msecs"

Errors
=====
JVM ScheduledThreadPoolExecutor error
-----
avg:  88.53232
max:  181
p99:  181
p95:  175
p75:  136
p50:  90

Rigui error
-----
avg:  18.88236
max:  234
p99:  185
p95:  116
p75:  14
p50:  3
```
The results indicates:

* Rigui is slower at enqueuing tasks, which might because rigui uses
  STM internally
* Rigui has better accuracy when tasks emitted in a short time. Rigui
  has way smaller error for 75%-95% tasks.

## Under the hood

This library is created with inspiration from
[this blog post about Kafka's timer
improvement](http://www.confluent.io/blog/apache-kafka-purgatory-hierarchical-timing-wheels). And
you can find more material about various timer implementation from
[this
paper](http://blog.acolyer.org/2015/11/23/hashed-and-hierarchical-timing-wheels/).

Generally speaking, the timing wheels implementation trades accuracy
for throughout. It aggregates timer tasks into a few buckets and runs
these buckets with a small number of actual timers. So timing wheels
is specifically optimized for scenarios with a large number of timer
tasks, also to be triggered in a narrow window. For instance, tracking
request timeout in asynchronous environment, or ping/pong timeout for
a large number of connections.

For a single task, the hierarchical timing wheels might create
multiple timers (depends on task delay, tick size and bucket
count). So there is minor overhead when dealing with small number of
tasks.

The accuracy is controlled via `tick` parameter. Tasks to be triggered
within `[ tick * n, tick * (n+1) )` will be put into the same bucket
and be triggered at the same time theoretically. If you want better
accuracy, you may set `tick` to a small value such as 1ms. But note
that finer `tick` may lead to more actual timers and reduce the
throughout.

The second parameter `bucket-count` decides how many buckets will be
on a single wheel. For this hierarchical wheels, it also decides the
tick side on the nth wheel (where n > 1, n counts from 1), that is
`tick * (bucket-count**(n-1))`. The larger `bucket-count` you set, the
more internal timers you will have. But in most case it's still less
than your task count significantly.

Let tick = 1, bucket-count = 8, the wheels could be visualized like:

![htw](https://cloud.githubusercontent.com/assets/221942/13547327/64599128-e309-11e5-8a7f-4ffbb2b8b9e9.png)

A task with delay 5 will be put onto the first wheel, while a delay of
350 will be put onto the third.

*If you know good free software to draw this please kindly let me
 know.*

## License

Copyright © 2015-2016 Ning Sun

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
