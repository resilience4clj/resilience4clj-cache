(ns resilience4clj-cache.core
  (:refer-clojure :exclude [load])
  (:import (javax.cache Caching)
           (javax.cache.configuration Configuration
                                      MutableConfiguration
                                      MutableCacheEntryListenerConfiguration
                                      Factory)
           (javax.cache.event CacheEntryEventFilter
                              CacheEntryCreatedListener
                              CacheEntryExpiredListener
                              CacheEntryRemovedListener
                              CacheEntryUpdatedListener)
           (java.time Duration)))

(defn ^:private anom-map
  [category msg]
  {:resilience4clj.anomaly/category (keyword "resilience4clj.anomaly" (name category))
   :resilience4clj.anomaly/message msg})

(defn ^:private anomaly!
  ([name msg]
   (throw (ex-info msg (anom-map name msg))))
  ([name msg cause]
   (throw (ex-info msg (anom-map name msg) cause))))

(defn ^:private get-failure-handler [{:keys [fallback]}]
  (if fallback
    (fn [& args] (apply fallback args))
    (fn [& args] (throw (-> args last :cause)))))

;; FIXME: manage cacheOnException and/or cacheOnResult (they are confusing in the docs)
#_(defn ^:private config-data->cache-config
    [{:keys [max-attempts wait-duration interval-function]}]
    (.build
     (cond-> (CacheConfig/custom)
       max-attempts  (.maxAttempts max-attempts)
       wait-duration (.waitDuration (Duration/ofMillis wait-duration))
       interval-function (.intervalFunction interval-function))))

;; FIXME: cache config does not expose wait duration directly - this is wrong
(defn ^:private cache-config->config-data
  [cache-config]
  {:max-attempts  (.getMaxAttempts cache-config)
   :interval-function (.getIntervalFunction cache-config)})

(defmulti ^:private event->data
  (fn [e]
    (-> e .getEventType .toString keyword)))

(defn ^:private base-event->data [e]
  {:event-type (-> e .getEventType .toString keyword)
   :cache-name (.getName e)
   :number-of-cache-attempts (.getNumberOfCacheAttempts e)
   :creation-time (.getCreationTime e)
   :last-throwable (.getLastThrowable e)})

(defn ^:private ellapsed-event->data [e]
  (merge (base-event->data e)
         {:ellapsed-duration (-> e .getElapsedDuration .toNanos)}))

(defmethod event->data :HIT [e]
  (base-event->data e))

(defmethod event->data :MISS [e]
  (base-event->data e))

(defmethod event->data :ERROR [e]
  (base-event->data e))

#_(defn ^:private event-consumer [f]
    (reify EventConsumer
      (consumeEvent [this e]
        (println "event-consumer being called")
        (let [data (event->data e)]
          (f data)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_(defn create
    [cache]
    (Cache/of cache))

#_(defn config
    [cache]
    (-> cache
        .getCacheConfig
        cache-config->config-data))

#_(defn decorate
    ([f cache-context]
     (decorate f cache-context nil))
    ([f cache-context {:keys [cache-key] :as opts}]
     (fn [& args]
       (let [key-fn (or cache-key (fn [& args'] (.hashCode args')))
             callable (reify Callable (call [_] (apply f args)))
             decorated-callable (Cache/decorateCallable cache-context callable)
             failure-handler (get-failure-handler opts)
             ;;lambda (Try/ofCallable decorated-callable)
             result (.apply decorated-callable (apply key-fn args))]
         result
         #_(if (.isSuccess result)
             (.get result)
             (let [args' (-> args vec (conj {:cause (.getCause result)}))]
               (apply failure-handler args')))))))

#_(defn metrics
    [cache-context]
    (let [metrics (.getMetrics cache-context)]
      {:number-of-cache-hits (.getNumberOfCacheHits metrics)
       :number-of-cache-misses (.getNumberOfCacheMisses metrics)}))

#_(defn listen-event
    [cache event-key f]
    (let [event-publisher (.getEventPublisher cache)
          consumer (event-consumer f)]
      (case event-key
        :success (.onSuccess event-publisher consumer)
        :error (.onError event-publisher consumer)
        :ignored-error (.onIgnoredError event-publisher consumer)
        :cache (.onCache event-publisher consumer))))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



(comment
  (def provider (javax.cache.Caching/getCachingProvider "org.cache2k.jcache.provider.JCacheProvider"))

  (def cm (.getCacheManager provider))

  (import org.cache2k.jcache.ExtendedMutableConfiguration)

  (def cache-proper (.createCache cm "cache-name2"
                                  (org.cache2k.jcache.ExtendedMutableConfiguration/of
                                   (-> (org.cache2k.Cache2kBuilder/of java.lang.String java.lang.String)
                                       (.eternal true)
                                       (.entryCapacity 100)))))

  (def cache-wrapper (Cache/of cache-proper))

  (def decorated-callable (Cache/decorateCallable cache-wrapper (reify Callable (call [_] "Hey buddy!"))))

  (def result (Try/ofCallable decorated-callable))


  (.apply decorated-callable "key3")


  (.getNumberOfCacheHits (.getMetrics cache-wrapper))
  (.getNumberOfCacheMisses (.getMetrics cache-wrapper)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(comment
  (defn ext-call [n]
    (Thread/sleep 1000)
    (str "Hello " n "!"))

  (require '[resilience4clj-cache.cache2k :as cache2k])

  (def cache (cache2k/create "my-cache16" {:eternal true
                                           :entry-capacity 2}))

  (def cache2 (cache2k/create "my-cache20" {:entry-capacity 100
                                            :refresh-ahead? true
                                            :expire-after-write 10000
                                            :key-class java.lang.String
                                            :val-class java.lang.String
                                            :suppress-exceptions? false
                                            :loader (cache2k/loader
                                                     (fn [n]
                                                       (println "in the loader" n)
                                                       "From loader"))}))

  (def cache-context (create cache))

  (def cache-context2 (create cache2))

  (defn ext-call2 [n i]
    (Thread/sleep 1000)
    {:name n
     :val i
     :rand (rand-int i)})

  (def decorated-cache-call (decorate ext-call cache-context2
                                      {:cache-key (fn [n]
                                                    (println "cache key" n)
                                                    n)}))

  (def decorated-cache-call2 (decorate ext-call2 cache-context))

  (time (decorated-cache-call "Tiago"))

  (decorated-cache-call2 "Tiago" 6001)

  (dotimes [n 100]
    (decorated-cache-call "Tiago2"))

  (metrics cache-context2))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(def provider (Caching/getCachingProvider))

(def manager (.getCacheManager provider))


(def listener-factory (reify Factory
                        (create [_]
                          (reify CacheEntryCreatedListener
                            (onCreated [_ e]
                              (-> e
                                  clojure.reflect/reflect
                                  clojure.pprint/pprint))))))

(def filter-factory nil #_(reify Factory
                            (create [_]
                              (reify CacheEntryEventFilter
                                (evaluate [_ e]
                                  true)))))

(def old-value-required? true)

(def synchronous? false)

(def listener-config (MutableCacheEntryListenerConfiguration.
                      ^Factory listener-factory
                      ^Factory filter-factory
                      old-value-required?
                      synchronous?))

(def config (-> (MutableConfiguration.)
                (.addCacheEntryListenerConfiguration listener-config)))

(.destroyCache manager "cache-name")

(def cache (.createCache manager "cache-name"
                         config))

(.containsKey cache "a")

(.put cache "a" {:a 1})

(.get cache "a")

#_(.getName cache)




(comment
  (def cache (create "cache-name" {:entry-capacity 100
                                   :expire-after-write 10000}))

  (def dec-call (decorate ext-call cache
                          {:refresh-ahead? true}))

  (dec-call "Tiago"))
