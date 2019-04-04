(ns resilience4clj-cache.core
  (:refer-clojure :exclude [load])
  (:import (javax.cache Caching)
           
           (javax.cache.configuration MutableConfiguration
                                      MutableCacheEntryListenerConfiguration
                                      Factory)

           (javax.cache.event CacheEntryExpiredListener)

           (javax.cache.expiry ExpiryPolicy
                               EternalExpiryPolicy
                               ModifiedExpiryPolicy
                               Duration)

           (java.util.concurrent TimeUnit)

           (java.time LocalDateTime)))

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
                          :creation-time (LocalDateTime/now)}
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
      (.setTypes java.lang.Object java.lang.Object)
      (.setExpiryPolicyFactory (get-expiry-policy opts))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(defn reset
  [{:keys [metrics] :as c}]
  (reset! metrics {:hits 0 :misses 0 :errors 0})
  c)

(defn listen-event
  [{:keys [listeners cache]} event-key f]
  (assert-anomaly! (some #(= event-key %)
                         #{:EXPIRED :HIT :MISSED :ERROR})
                   :invalid-event-key
                   "event-key must be one of :EXPIRED :HIT :MISSED :ERROR")
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
                  (println ":EXPIRED being called")
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
