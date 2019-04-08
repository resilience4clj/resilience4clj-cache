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
* [Manual Cache Manipulation](#manual-cache-manipulation)
* [Invalidating the Cache](#invalidating-the-cache)
* [Using as an Effect](#using-as-an-effect)
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

Resilience4clj cache does depends on a concrete implementation of a
caching engine to the JSR107 interfaces. Therefore, in order to use
Resilience4clj cache you need to choose a compatible caching engine.

This is a far from comprehensive list of options:

- [Cache2k](https://cache2k.org): simple embedded, in-memory cache system
- [Ehcache](http://www.ehcache.org): supports supports offheap storage
  and distributed, persistence via Terracotta
- [Infinispan](http://infinispan.org/): embedded caching as well as
  advanced functionality such as transactions, events, querying,
  distributed processing, off-heap and geographical failover.
- [Redisson](https://redisson.org): Redis Java client in-Memory data
  grid
- [Apache Ignite](https://ignite.apache.org): memory-centric
  distributed database, caching, and processing platform for
  transactional, analytical, and streaming workloads delivering
  in-memory speeds at petabyte scale

For this getting started let's use a simple embedded, in-memory cache
via Infinispan. Add it as a dependency to your `deps.edn` file:

``` clojure
org.infinispan/infinispan-embedded {:mvn/version "9.1.7.Final"}
```

Or, if you  are using `lein` instead,  add it as a  dependency to your
`project.clj` file:

``` clojure
[org.infinispan/infinispan-embedded "9.1.7.Final"]
```

Once both Resilience4clj cache and a concrete cache engine in place
you can require the library:

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

By default the `create` function will set your cache as eternal so
every single call to `protected` above will return `"Hello World!"`
for long as the cache entry is in memory (or until the cache is
manually invalidated - see function `invalidate!` below).

## Cache Settings

If you simply call the `create` function providing a cache name,
Resilience4clj cache will capture the default caching provider from
your classpath and then use sensible and simple settings to bring your
cache system up. These steps should cover many of the basic caching
scenarios.

The `create` supports a second map argument for further
configurations.

There are two very basic fine-tuning settings available:

1. `:eternal?` - whether this cache will retain its entries forever or
   not. Caching engines might still discard entries if certain
   conditions are met (i.e. full memory) so this should be used as an
   indication of intent more than a solid dependency. Default `true`.
2. `:expire-after` - if you don't want an eternal cache entry, chances
   are you would prefer entries that expire after a certain amount of
   time. You can specify any amount of milliseconds of at least 1000
   or higher (if specified, `:eternal?` is automatically turned off).

For more advanced scenarios, you might want to set up your caching
engine with all sorts of whistles and belts. In these scenarios you
will need to provide a combination of factory functions to cover for
your particular need:

1. `:provider-fn` - function that receives the options map sent to
   `create` and must return a concrete implementation of a
   `javax.cache.spi.CachingProvider`. If `:provider-fn` is not
   specified, Resilience4clj will simply get the default caching
   provider on your clasppath.
2. `:manager-fn` - function that receives the `CachingProvider` as a
   first argument and the options map sent to `create` as the second
   one and must return a concrete implementation of a
   `CacheManager`. If `:manager-fn` is not specified, Resilience4clj
   will simply ask the provider for its default `CacheManager`.
3. `:config-fn` - function that receives the options maps sent to
   `create` and must return any concrete implementation of
   `javax.cache.configuration.Configuration`. If `:config-fn` is not
   specified, Resilience4clj will create a `MutableConfiguration` and
   use `:eternal?` and `:expire-after` above to do some basic fine
   tuning on the config.

Things to notice when setting up your cache using these factory
functions above:

* Among many other impactful settings, your expiration policies will
  definitely affect the way that your cache behaves.
* If your configuration does not expose mutable abilities such as the
  method `registerCacheEntryListener`, then listening the expiration
  events as documented in the [Events](#events) section is not going
  to work.
* Resilience4clj cache expects the `<K, V>` of the Cache to be
  `java.lang.String, java.lang.Object`. Other settings have not been
  tested and might not work.

Here is an example creating a cache that expires in a minute:

``` clojure
(def cache (c/create {:expire-after 60000}))
```

The function `config` returns the configuration of a cache in case
you need to inspect it. Example:

``` clojure
(c/config cache)
=> {:provider-fn #object[resilience4clj-cache.core$get-provider...
    :manager-fn #object[resilience4clj-cache.core$get-manager...
    :config-fn #object[resilience4clj-cache.core$get-config...
    :eternal? true
    :expire-after nil}
```

## Fallback Strategies

When decorating your function with a cache you can opt to have a
fallback function. This function will be called instead of an
exception being thrown when the call would fail (its traditional
throw). This feature can be seen as an obfuscation of a try/catch to
consumers.

This is particularly useful if you want to obfuscate from consumers
that the external dependency failed. Example:

``` clojure
(def cache (c/create "my-cache"))

(defn hello [person]
  ;; hypothetical flaky, external HTTP request
  (str "Hello " person))

(def cached-hello
  (c/decorate hello
              {:fallback (fn [e person]
                           (str "Hello from fallback to " person))}))
```

The signature of the fallback function is the same as the original
function plus an exception as the first argument (`e` on the example
above). This exception is an `ExceptionInfo` wrapping around the real
cause of the error. You can inspect the `:cause` node of this
exception to learn about the inner exception:

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

## Manual Cache Manipulation

By default Resilience4clj cache can be used as a decorator to your
external calls and it will take care of basic caching for you. In some
circumstances though you might want to interact directly with its
cache. One such situation is when [using the cache as an
effect](#using-as-an-effect).

There are two functions to directly manipulate the cache:

1. `(put! <cache> <args> <value>)`: will put the `<value>` in
   `<cache>` keyed by `<args>`
2. `(get <cache> <args>)`: will get the cached value from `<cache>`
   keyed by `<args>`

`<args>` can be any Clojure object that supports `.toString`.

Caveats when manually using the cache:

1. You are not using any of the automatic, decorated features of the
   cache - therefore you've got no fallback for instance
2. Resilience4clj cache internally segments the cache for every
   function that it decorates and every combination of arguments sent
   to the function. When used manually, only one "caching space" is
   used. Therefore, if the same args are used in different places with
   different semantic meanings you will still get the same values from
   the cache.
3. The `put!` and `get` interfaces prefer dealing with `<args>` as a
   list. If you don send a `seqable?` as `<args>`, whatever parameter
   you send will be transformed into a list. Therefore (due to the
   bullet above) sending `:foobar` is equivalent to `'(:foobar)`

See [using the cache as an effect](#using-as-an-effect) for a use case
where direct manipulation of the cache is very useful.

## Invalidating the Cache

By default Resilience4clj cache uses an eternal cache (this can be
[set up differently if you want](#cache-settings)) therefore, you
might eventually want to invalidate the cache altogether.

In order to do so, use the function `invalidate!`. In the following
code, the `cache` will be invalidated:

``` clojure
(c/invalidate! cache)
```

## Using as an Effect

TBD

## Metrics

TBD: get the real metrics

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

TBD: get the real events

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

TBD: copy something from circuit breaker

When a retry exhausts all the attempts it will throw the very last
exception returned from the decorated, failing function call.

## Composing Further

TBD: review/update plus refer back to using as an effect

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
