[about]: https://github.com/luchiniatwork/resilience4clj-circuitbreaker/blob/master/docs/ABOUT.md
[breaker]: https://github.com/luchiniatwork/resilience4clj-circuitbreaker/
[circleci-badge]: https://circleci.com/gh/luchiniatwork/resilience4clj-cache.svg?style=shield&circle-token=
[circleci]: https://circleci.com/gh/luchiniatwork/resilience4clj-cache
[clojars-badge]: https://img.shields.io/clojars/v/resilience4clj/resilience4clj-cache.svg
[clojars]: http://clojars.org/resilience4clj/resilience4clj-cache
[github-issues]: https://github.com/luchiniatwork/resilience4clj-cache/issues
[license-badge]: https://img.shields.io/badge/license-MIT-blue.svg
[license]: ./LICENSE
[status-badge]: https://img.shields.io/badge/project%20status-alpha-brightgreen.svg

# Resilience4Clj Cache

[![CircleCI][circleci-badge]][circleci]
[![Clojars][clojars-badge]][clojars]
[![License][license-badge]][license]
![Status][status-badge]

Resilience4clj is a lightweight fault tolerance library set built on
top of GitHub's Resilience4j and inspired by Netflix Hystrix. It was
designed for Clojure and functional programming with composability in
mind.

Read more about the [motivation and details of Resilience4clj
here][about].

Resilience4Clj Cache lets you decorate a function call with a
distributed caching infrastructure as provided by any `javax.cache`
(JSR107) provider. The resulting function will behave as an advanced
form of memoization (think of it as distributed memoization with
monitoring and metrics).

## Table of Contents

* [Getting Started](#getting-started)
* [Cache Settings](#cache-settings)
* [Fallback Strategies](#fallback-strategies)
* [Metrics](#metrics)
* [Events](#events)
* [Exception Handling](#exception-handling)
* [Composing Further](#composing-further)
* [Bugs](#bugs)
* [Help!](#help)

## Getting Started

Add `resilience4clj/resilience4clj-cache` as a dependency to your
`deps.edn` file:

``` clojure
resilience4clj/resilience4clj-cache {:mvn/version "0.1.0"}
```

If you are using `lein` instead, add it as a dependency to your
`project.clj` file:

``` clojure
[resilience4clj/resilience4clj-cache "0.1.0"]
```

Require the library:

``` clojure
(require '[resilience4clj-cache.core :as c])
```

Then create a cache calling the function `create`:

``` clojure
(def cache (c/create "my-cache"))
```

Now you can decorate any function you have with the cache you just
defined.

For the sake of this example, let's create a function that takes
1000ms to return:

``` clojure
(defn slow-hello []
  (Thread/sleep 1000)
  "Hello World!")
```

You can now create a decorated version of your `slow-hello` function
above combining the `cache` we created before like this:

``` clojure
(def protected (c/decorate slow-hello cache))
```

When you call `protected` for the first time it will take around
1000ms to run because of the timeout we added there. Subsequent calls
will return virtually instanteneaously because the return of the
function has been cached in memory.

``` clojure
(time (protected))
"Elapsed time: 1001.462526 msecs"
Hello World!

(time (protected))
"Elapsed time: 1.238522 msecs"
Hello World!
```

By default the `create` function will create an in-memory cache that
will retain results for as long as it's in memory (or until manually
invalidated). Read below for more advanced options.

## Cache Settings

:caching-provider --- FIXME: do we need this if only one is allowed?
- org.cache2k.jcache.provider.JCacheProvider  --- cache2k: https://cache2k.org
- ehcache
- infinispan
- redisson

- blazingcache.jcache.BlazingCacheProvider --- blazing cache: https://blazingcache.org/-


:configurator - fn that returns a config (overrules the other two)

:expire-after (default 6000 - at least 1000)
:eternal? (default true)

https://jcp.org/aboutJava/communityprocess/implementations/jsr107/index.html



When creating a retry, you can fine tune three of its settings:

1. `:max-attempts` - the amount of attempts it will try before giving
   up. Default is `3`.
2. `:wait-duration` - the duration in milliseconds between attempts.
   Default is `500`.
3. `:interval-function` - sometimes you need a more advanced strategy
   between attempts. Some systems react better with a progressive
   backoff for instance (you can start retrying faster but increasing
   the waiting time in case the remote system is offline). For these
   cases you can specify an interval function that will control this
   waiting time. See below for more. Default here is a linear function
   of `:wait-duration` intervals.

These three options can be sent to `create` as a map. In the following
example, any function decorated with `retry` will be attempted for 10
times with in 300ms intervals.

``` clojure
(def retry (create {:max-attempts 10
                    :wait-duration 300}))
```

Resilience4clj provides a series of commonly-used interval
functions. They are all in the namespace
`resilience4clj-retry.interval-functions`:

* `of-default` - basic linear function with 500ms intervals
* `of-millis` - linear function with a specified interval in
  milliseconds
* `of-randomized` - starts with an initial, specified interval in
  milliseconds and then randomizes by an optional factor on subsequent
  attempts
* `of-exponential-backoff` - starts with an initial, specified
  interval in milliseconds and then backs off by an optional
  multiplier on subsequent calls (default multiplier is 1.5).
* `of-exponential-random-backoff` - starts with an initial, specified
  interval in milliseconds and then backs off by an optional
  multiplier and randomizes by an optional factor on subsequent calls.

## Fallback Strategies

When decorating your function with a retry you can opt to have a
fallback function. This function will be called instead of an
exception being thrown when the retry gives up after reaching the
max-attempts or when the call would fail (traditional throw). This
feature can be seen as an obfuscation of a try/catch to consumers.

This is particularly useful if you want to obfuscate from consumers
that the retry and/or that the external dependency failed. Example:

``` clojure
(def retry (r/create "hello-service-retry"))

(defn hello [person]
  ;; hypothetical flaky, external HTTP request
  (str "Hello " person))

(def retry-hello
  (r/decorate hello
              {:fallback (fn [person e]
                           (str "Hello from fallback to " person))}))
```

The signature of the fallback function is the same as the original
function plus an exception (`e` on the example above). This exception
is an `ExceptionInfo` wrapping around the real cause of the error. You
can inspect the `:cause` node of this exception to learn about the
inner exception:

``` clojure
(defn fallback-fn [e]
  (str "The cause is " (-> e :cause)))
```

For more details on [Exception Handling](#exception-handling) see the
section below.

When considering fallback strategies there are usually three major
strategies:

1. **Failure**: the default way for Resilience4clj - just let the
   exceptiohn flow - is called a "Fail Fast" approach (the call will
   fail fast once the breaker is open). Another approach is "Fail
   Silently". In this approach the fallback function would simply hide
   the exception from the consumer (something that can also be done
   conditionally).
2. **Content Fallback**: some of the examples of content fallback are
   returning "static content" (where a failure would always yield the
   same static content), "stubbed content" (where a failure would
   yield some kind of related content based on the paramaters of the
   call), or "cached" (where a cached copy of a previous call with the
   same parameters could be sent back).
3. **Advanced**: multiple strategies can also be combined in order to
   create even better fallback strategies.

## Metrics

The function `metrics` returns a map with the metrics of the retry:

``` clojure
(r/metrics my-retry)

=> {:number-of-successful-calls-without-retry-attempt 0,
    :number-of-failed-calls-without-retry-attempt 0,
    :number-of-successful-calls-with-retry-attempt 0,
    :number-of-failed-calls-with-retry-attempt 0}
```

The nodes should be self-explanatory.

## Events

You can listen to events generated by your retries. This is
particularly useful for logging, debugging, or monitoring the health
of your retries.

``` clojure
(def my-retry (r/create "my-retry"))

(cb/listen-event my-retry
                 :RETRY
                 (fn [evt]
                   (println (str "Received event " (:event-type evt)))))
```

There are four types of events:

1. `:SUCCESS` - informs that a call has been tried and succeeded
2. `:ERROR` - informs that a call has been retried, but still failed
5. `:IGNORED_ERROR` - informs that an error has been ignored
6. `:RETRY` - informs that a call has been tried, failed and will now
   be retried

Notice you have to listen to a particular type of event by specifying
the event-type you want to.

All events receive a map containing the `:event-type`, the
`:retry-name`, the event `:creation-time`, a
`:number-of-retry-attempts`,and the `:last-throwable`.

## Exception Handling

When a retry exhausts all the attempts it will throw the very last
exception returned from the decorated, failing function call.

## Composing Further

Resilience4clj is composed of [several modules][about] that
easily compose together. For instance, if you are also using the
[circuit breaker module][breaker] and assuming your import and basic
settings look like this:

``` clojure
(ns my-app
  (:require [resilience4clj-circuitbreaker.core :as cb]
            [resilience4clj-retry.core :as r]))

;; create a retry with default settings
(def retry (r/create "my-retry"))

;; create circuit breaker with default settings
(def breaker (cb/create "HelloService"))

;; flaky function you want to potentially retry
(defn flaky-hello []
  ;; hypothetical request to a flaky server that might fail (or not)
  "Hello World!")
```

Then you can create a protected call that combines both the retry and
the circuit breaker:

``` clojure
(def protected-hello (-> flaky-hello
                         (r/decorate retry)
                         (cb/decorate breaker)))
```

The resulting function on `protected-hello` will trigger the breaker
in case of a failed retries.

## Bugs

If you find a bug, submit a [Github issue][github-issues].

## Help

This project is looking for team members who can help this project
succeed!  If you are interested in becoming a team member please open
an issue.

## License

Copyright © 2019 Tiago Luchini

Distributed under the MIT License.
