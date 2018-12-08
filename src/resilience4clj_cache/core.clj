(ns resilience4clj-cache.core
  (:refer-clojure :exclude [load])
  (:import (javax.cache Caching)
           
           (javax.cache.configuration Configuration
                                      MutableConfiguration
                                      MutableCacheEntryListenerConfiguration
                                      CompleteConfiguration
                                      Factory)
           
           (javax.cache.event CacheEntryEventFilter
                              CacheEntryListener
                              CacheEntryCreatedListener
                              CacheEntryExpiredListener
                              CacheEntryRemovedListener
                              CacheEntryUpdatedListener)
           
           (javax.cache.expiry ExpiryPolicy
                               AccessedExpiryPolicy
                               CreatedExpiryPolicy
                               EternalExpiryPolicy
                               ModifiedExpiryPolicy
                               TouchedExpiryPolicy
                               Duration)

           (java.util.concurrent TimeUnit)))

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

;; events:
;; :hit
;; :miss
;; :error

;; is in cache? then hit++,  get -> out -> event hit
;; is not in cache? then miss++, fetch, put -> out -> event miss
;; if errored error++ -> event error

;; :created ->
;; :updated
;; :removed
;; :expired -> if refresh-ahead then

;; metrics
;; number-of-cache-hits
;; mumber-of-cache-misses
;; number-of-errors
;; should???:
;; number-of-created
;; number-of-updated
;; number-of-removed
;; number-of-expired


(def provider (Caching/getCachingProvider))

(def manager (.getCacheManager provider))


(def created-listener-factory (reify Factory
                                (create [_]
                                  (reify CacheEntryCreatedListener
                                    (onCreated [_ e]
                                      (println "created event called")
                                      #_(-> e
                                            clojure.reflect/reflect
                                            clojure.pprint/pprint))))))

(def expired-listener-factory (reify Factory
                                (create [_]
                                  (reify CacheEntryExpiredListener
                                    (onExpired [_ e]
                                      (println "expired event called")
                                      #_(-> e
                                            clojure.reflect/reflect
                                            clojure.pprint/pprint))))))

(def removed-listener-factory (reify Factory
                                (create [_]
                                  (reify CacheEntryRemovedListener
                                    (onRemoved [_ e]
                                      (println "removed event called")
                                      #_(-> e
                                            clojure.reflect/reflect
                                            clojure.pprint/pprint))))))

(def updated-listener-factory (reify Factory
                                (create [_]
                                  (reify CacheEntryUpdatedListener
                                    (onUpdated [_ e]
                                      (println "updated event called")
                                      #_(-> e
                                            clojure.reflect/reflect
                                            clojure.pprint/pprint))))))


(def old-value-required? true)

(def synchronous? false)

(def created-listener-config (MutableCacheEntryListenerConfiguration.
                              ^Factory created-listener-factory
                              nil
                              old-value-required?
                              synchronous?))

(def expired-listener-config (MutableCacheEntryListenerConfiguration.
                              ^Factory expired-listener-factory
                              nil
                              old-value-required?
                              synchronous?))

(def removed-listener-config (MutableCacheEntryListenerConfiguration.
                              ^Factory removed-listener-factory
                              nil
                              old-value-required?
                              synchronous?))

(def updated-listener-config (MutableCacheEntryListenerConfiguration.
                              ^Factory updated-listener-factory
                              nil
                              old-value-required?
                              synchronous?))

(def expiry-policy-factory (reify Factory
                             (create [_]
                               (ModifiedExpiryPolicy. (Duration. TimeUnit/SECONDS 60)))))

(def config (-> (MutableConfiguration.)
                (.addCacheEntryListenerConfiguration created-listener-config)
                (.addCacheEntryListenerConfiguration expired-listener-config)
                (.addCacheEntryListenerConfiguration removed-listener-config)
                (.addCacheEntryListenerConfiguration updated-listener-config)
                (.setTypes java.lang.Object java.lang.Object)
                (.setExpiryPolicyFactory expiry-policy-factory)))

(.destroyCache manager "cache-name")

(def cache (.createCache manager "cache-name"
                         config))

(.containsKey cache 1)

(.put cache 1 {:a 1})

(.get cache 1)

(.remove cache 1)

#_(.getName cache)

(defn ^:private assert-anomaly!
  [assertion? category msg]
  (when (not assertion?) (anomaly! category msg)))

(defn ^:private get-caching-provider
  [{:keys [caching-provider]}]
  (if caching-provider
    (Caching/getCachingProvider caching-provider)
    (Caching/getCachingProvider)))

(defn ^:private get-expiry-policy
  [{:keys [expire-after eternal?]}]
  (println "aqui" expire-after eternal?)
  (when expire-after
    (assert-anomaly! (> expire-after 1000)
                     :invalid-expire-after
                     ":expire-after must be at least 1000"))
  (let [expire-after' (or expire-after 60000)]
    (if (or eternal? (and (nil? eternal?) (nil? expire-after)))
      (reify Factory
        (create [_]
          (EternalExpiryPolicy.)))
      (reify Factory
        (create [_]
          (ModifiedExpiryPolicy. (Duration. TimeUnit/MILLISECONDS
                                            expire-after')))))))

(defn ^:private get-expired-listener
  []
  (reify Factory
    (create [_]
      (reify CacheEntryExpiredListener
        (onExpired [_ e]
          (println "expired event called")
          #_(-> e
                clojure.reflect/reflect
                clojure.pprint/pprint))))))


(defn ^:private build-expired-listener-config
  []
  (let [old-value-required? false
        synchronous? false]
    (MutableCacheEntryListenerConfiguration. ^Factory (get-expired-listener)
                                             nil ;; listener filter not needed
                                             old-value-required?
                                             synchronous?)))

(defn ^:private build-config
  [{:keys [] :as opts}]
  (-> (MutableConfiguration.)
      (.addCacheEntryListenerConfiguration (build-expired-listener-config))
      (.setTypes java.lang.Object java.lang.Object)
      (.setExpiryPolicyFactory (get-expiry-policy opts))))


(defn create
  ([n]
   (create n nil))
  ([n opts]
   (let [provider (get-caching-provider opts)
         manager (.getCacheManager provider)
         config (build-config opts)]
     (.destroyCache manager n)
     {:metrics (atom {:hits 0 :misses 0 :errors 0})
      :cache (.createCache manager n config)})))

;; FIXME trigger events
(defn decorate
  ([f cache]
   (decorate f cache nil))
  ([f {:keys [cache metrics] :as c} {:keys [fallback refresh-ahead-count] :as opts}]
   (fn [& args]
     (try
       (if (.containsKey cache args)
         (do
           (swap! metrics update :hits inc)
           (.get cache args))
         (do
           (let [out (apply f args)]
             (swap! metrics update :misses inc)
             (.put cache args out)
             out)))
       ;;FIXME deal with fallback
       (catch Throwable t
         (swap! metrics update :errors inc)
         (throw t))))))

;;FIXME: for some reason this does not work despite the fact it should
(defn config
  [{:keys [cache]}]
  (let [cfg (.getConfiguration cache CompleteConfiguration)
        expiry-policy (-> cfg
                          .getExpiryPolicyFactory
                          .create)]
    (println "in the config" (type expiry-policy))
    (if (instance? EternalExpiryPolicy expiry-policy)
      {:eternal? true}
      {:expire-after (-> expiry-policy
                         .getExpiryForUpdate
                         .getDurationAmount)})))

(defn metrics
  [{:keys [metrics]}]
  (deref metrics))

(comment

  (defn ext-call [n]
    (Thread/sleep 1000)
    (str "Hello " n "!"))
  
  (def cache (create "cache-name" {:expire-after 5000}))

  (def dec-call (decorate ext-call cache))

  (dec-call "Tiago")

  (dotimes [n 100]
    (dec-call "Bla"))
  
  (metrics cache)

  )
