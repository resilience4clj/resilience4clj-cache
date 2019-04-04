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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


;; TBD: here

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


(comment
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

  #_(.getName cache))






(defn ^:private assert-anomaly!
  [assertion? category msg]
  (when (not assertion?) (anomaly! category msg)))

(defn ^:private get-caching-provider
  [{:keys [caching-provider]}]
  (if caching-provider
    (Caching/getCachingProvider caching-provider)
    (Caching/getCachingProvider)))

;; FIXME reconsider whether making eternal? by default is a good idea
(defn ^:private get-expiry-policy
  [{:keys [expire-after eternal?]}]
  (when expire-after
    (assert-anomaly! (>= expire-after 1000)
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

(defn ^:private expired-event->data [e]
  {:event-type :EXPIRED
   :cache-name (-> e .getSource .getName)
   :key (.getKey e)
   :creation-time (java.time.LocalDateTime/now)})

(defn ^:private trigger-event
  ([c evt-type k]
   (trigger-event c evt-type k nil))
  ([{:keys [cache listeners]} evt-type k opts]
   (let [evt-data (merge {:event-type evt-type
                          :cache-name (.getName cache)
                          :key k
                          :creation-time (java.time.LocalDateTime/now)}
                         opts)]
     (doseq [f (get @listeners evt-type)]
       (f evt-data)))))

(defn ^:private get-expired-listener
  [f]
  (reify Factory
    (create [_]
      (reify CacheEntryExpiredListener
        (onExpired [_ e]
          (f (expired-event->data e)))))))

(defn ^:private build-expired-listener-config
  [f]
  (let [old-value-required? false
        synchronous? false]
    (MutableCacheEntryListenerConfiguration. ^Factory (get-expired-listener f)
                                             nil ;; listener filter not needed
                                             old-value-required?
                                             synchronous?)))

(defn ^:private build-config
  [{:keys [] :as opts}]
  (-> (MutableConfiguration.)
      #_(.addCacheEntryListenerConfiguration (build-expired-listener-config))
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
      :listeners (atom {})
      :cache (.createCache manager n config)
      :config config})))

(defn config
  [{:keys [config]}]
  (let [expiry-policy (-> config
                          .getExpiryPolicyFactory
                          .create)]
    (if (instance? EternalExpiryPolicy expiry-policy)
      {:eternal? true}
      {:expire-after (-> expiry-policy
                         .getExpiryForUpdate
                         .getDurationAmount)})))



;; FIXME trigger events
(defn decorate
  ([f cache]
   (decorate f cache nil))
  ([f {:keys [cache metrics] :as c} {:keys [fallback] :as opts}]
   (fn [& args]
     (try
       (if (.containsKey cache args)
         (do
           (swap! metrics update :hits inc)
           (.get cache args)
           (trigger-event c :HIT args))
         (do
           (let [out (apply f args)]
             (swap! metrics update :misses inc)
             (.put cache args out)
             (trigger-event c :MISSED args)
             out)))
       ;;FIXME deal with fallback
       (catch Throwable t
         (swap! metrics update :errors inc)
         (trigger-event c :ERROR args {:cause t})
         (throw t))))))



(defn metrics
  [{:keys [metrics]}]
  (deref metrics))

;; TBD test
(defn reset
  [cache]
  (update cache :metrics (atom {:hits 0 :misses 0 :errors 0})))

;; TBD validate event-keys
;; :EXPIRED

;; :HIT
;; :MISSED

;; :ERROR
(defn listen-event
  [{:keys [listeners cache]} event-key f]
  (let [coll (get listeners event-key)]
    (if (= :EXPIRED event-key)
      (-> cache
          (.registerCacheEntryListener (build-expired-listener-config f)))
      (swap! listeners assoc event-key (conj coll f)))))

(do

  (defn ext-call [n]
    (Thread/sleep 1000)
    (str "Hello " n "!"))
  
  (def cache (create "cache-name" {:expire-after 5000}))

  (def dec-call (decorate ext-call cache))

  (listen-event cache :EXPIRED
                (fn [evt]
                  (println ":EXPIRED bwing called")
                  (println evt)))

  (listen-event cache :HIT
                (fn [evt]
                  (println ":HIT being called")
                  (println evt)))

  (listen-event cache :MISSED
                (fn [evt]
                  (println ":MISSED being called")
                  (println evt)))

  (dec-call "Tiago")
  
  (dotimes [n 5]
    (dec-call "Bla"))
  
  (metrics cache)

  )
