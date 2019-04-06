(ns resilience4clj-cache.core
  (:refer-clojure :exclude [reset!])
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

           (java.time LocalDateTime)

           (java.security MessageDigest)

           (java.math BigInteger)))

(defn ^:private anom-map
  [category msg]
  {:resilience4clj.anomaly/category (keyword "resilience4clj.anomaly"
                                             (name category))
   :resilience4clj.anomaly/message msg})

(defn ^:private anomaly!
  ([name msg]
   (throw (ex-info msg (anom-map name msg))))
  ([name msg cause]
   (throw (ex-info msg (anom-map name msg) cause))))

(defn ^:private md5 [^String s]
  (let [algorithm (MessageDigest/getInstance "MD5")
        raw (.digest algorithm (.getBytes s))]
    (format "%032x" (BigInteger. 1 raw))))

(defn ^:private get-failure-handler [{:keys [fallback]}]
  (if fallback
    (fn [& args] (apply fallback args))
    (fn [& args] (throw (-> args last :cause)))))

(defn ^:private assert-anomaly!
  [assertion? category msg]
  (when (not assertion?) (anomaly! category msg)))

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

(defn ^:private get-fn-name
  [f]
  (str f))

(defn ^:private cache-entry-id
  [f args]
  (-> (conj args (get-fn-name f))
      .toString
      md5))

;; Attention: no :fn-name as it has no idea of the fn that triggered it
(defn ^:private expired-event->data [e]
  {:event-type :EXPIRED
   :cache-name (-> e .getSource .getName)
   :key (.getKey e)
   :creation-time (java.time.LocalDateTime/now)})

(defn ^:private trigger-event
  ([c evt-type fn-name k]
   (trigger-event c evt-type fn-name k nil))
  ([{:keys [cache listeners]} evt-type fn-name k opts]
   (let [evt-data (merge {:event-type evt-type
                          :cache-name (.getName cache)
                          :fn-name fn-name
                          :key k
                          :creation-time (LocalDateTime/now)}
                         opts)]
     (doseq [f (get @listeners evt-type)]
       (f evt-data)))))

(defn ^:private hit-cache!
  [{:keys [cache metrics] :as c} fn-name id]
  (swap! metrics update :hits inc)
  (trigger-event c :HIT fn-name id)
  (.get cache id))

(defn ^:private missed-cache!
  [{:keys [cache metrics] :as c} fn-name id new-value]
  (swap! metrics update :misses inc)
  (.put cache id new-value)
  (trigger-event c :MISSED fn-name id)
  new-value)

(defn ^:private get-expired-listener
  [handler]
  (reify Factory
    (create [_]
      (reify CacheEntryExpiredListener
        (onExpired [_ e]
          (doseq [evt e]
            (handler (expired-event->data evt))))))))

(defn ^:private build-expired-listener-config
  [handler]
  (let [old-value-required? false
        synchronous? false]
    (MutableCacheEntryListenerConfiguration. ^Factory (get-expired-listener handler)
                                             nil ;; listener filter not needed
                                             old-value-required?
                                             synchronous?)))

(defn ^:private get-provider
  [opts]
  (Caching/getCachingProvider))

(defn ^:private get-manager
  [provider opts]
  (.getCacheManager provider))

(defn ^:private get-config
  [opts]
  (-> (MutableConfiguration.)
      (.setTypes java.lang.String java.lang.Object)
      (.setExpiryPolicyFactory (get-expiry-policy opts))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private default-metrics []
  {:hits 0 :misses 0 :errors 0
   :manual-puts 0 :manual-gets 0})

(defn create
  ([n]
   (create n nil))
  ([n {:keys [provider-fn manager-fn config-fn]
       :or {provider-fn get-provider
            manager-fn  get-manager
            config-fn   get-config}
       :as opts}]
   (let [provider (provider-fn opts)
         manager (manager-fn provider opts)
         config (config-fn opts)]
     (.destroyCache manager n)
     {:metrics (atom (default-metrics))
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
  ([f {:keys [cache metrics] :as c} opts]
   (fn [& args]
     (let [id (cache-entry-id f args)
           fn-name (get-fn-name f)]
       (try
         (if (.containsKey cache id)
           (hit-cache! c fn-name id)
           (let [new-value (apply f args)]
             (missed-cache! c fn-name id new-value)))
         (catch Throwable t
           (swap! metrics update :errors inc)
           (trigger-event c :ERROR fn-name id {:cause t})
           (let [failure-handler (get-failure-handler opts)
                 args' (-> args vec (conj {:cause t}))]
             (apply failure-handler args'))))))))

(defn put!
  [{:keys [cache metrics] :as c} args value]
  (swap! metrics update :manual-puts inc)
  (let [id (cache-entry-id 'nofn-manual args)
        fn-name (get-fn-name 'nofn-manual)]
    (.put cache id value)
    (trigger-event c :MANUAL-PUT fn-name id)
    value))

(defn get!
  [{:keys [cache metrics] :as c} args]
  (swap! metrics update :hits inc)
  (let [id (cache-entry-id 'nofn-manual args)
        fn-name (get-fn-name 'nofn-manual)]
    (trigger-event c :MANUAL-GET fn-name id)
    (.get cache id)))

(defn invalidate!
  [{:keys [cache] :as c}]
  (.removeAll cache))

(defn metrics
  [{:keys [metrics]}]
  (deref metrics))

(defn reset!
  [{:keys [metrics] :as c}]
  (reset! metrics (default-metrics))
  c)

(defn listen-event
  [{:keys [listeners cache]} event-key f]
  (assert-anomaly! (some #(= event-key %)
                         #{:EXPIRED :HIT :MISSED :MANUAL-PUT :MANUAL-GET :ERROR})
                   :invalid-event-key
                   "event-key must be one of :EXPIRED :HIT :MISSED :MANUAL-PUT :MANUAL-GET :ERROR")
  (let [coll (get listeners event-key)]
    (if (= :EXPIRED event-key)
      (-> cache
          (.registerCacheEntryListener (build-expired-listener-config f)))
      (swap! listeners assoc event-key (conj coll f)))))

(do

  (defn ext-call [n]
    (Thread/sleep 1000)
    (str "Hello " n "!"))

  (defn ext-call2 [n]
    (Thread/sleep 1000)
    (str "Olá " n "!"))
  
  (defn fail-hello [n]
    (throw (ex-info "Hello failed :(" {:here :extra-data})))

  (defn conditional-hello
    ([n]
     (conditional-hello n nil))
    ([n {:keys [fail?]}]
     (Thread/sleep 1000)
     (if fail?
       (throw (ex-info "Hello failed :(" {:here :extra-data}))
       (str "Hello " n "!"))))

  (defn create-oscilating-hello
    [x]
    (let [cycle (atom :good)
          c (atom 0)]
      (fn [n]
        (if (= x @c)
          (do
            (swap! cycle #(if (= % :good) :bad :good))
            (reset! c 0)))
        (swap! c inc)
        (if (= :bad @cycle)
          (throw (ex-info "Hello failed :(" {:here :extra-data}))
          (do
            (Thread/sleep 1000)
            (str "Hello " n "!"))))))
  
  (def cache (create "cache-name" {:expire-after 5000}))

  (def dec-call (decorate ext-call cache))
  (def dec-call2 (decorate ext-call2 cache))

  (def protected (decorate fail-hello cache))

  (def protected-fallback (decorate fail-hello
                                    cache
                                    {:fallback (fn [n e]
                                                 (str "Failed with " e " for " n))}))
  
  #_(listen-event cache :EXPIRED
                  (fn [evt]
                    (println ":EXPIRED being called")
                    (println evt)))

  #_(listen-event cache :HIT
                  (fn [evt]
                    (println ":HIT being called")
                    (println evt)))

  #_(listen-event cache :MISSED
                  (fn [evt]
                    (println ":MISSED being called")
                    (println evt)))

  #_(listen-event cache :MANUAL-PUT
                  (fn [evt]
                    (println ":MANUAL-PUT being called")
                    (println evt)))

  #_(listen-event cache :MANUAL-GET
                  (fn [evt]
                    (println ":MANUAL-GET being called")
                    (println evt)))

  (dec-call "Tiago")
  (dec-call2 "Tiago")
  
  (time (dotimes [n 50]
          (dec-call "Bla")))
  
  (metrics cache)

  (put! cache [:a] "12")
  (get! cache [:a])
  

  ;; these tests don work because the cache invalidation happens at the
  ;; level of the collection of parameters
  
  (def eternal-cache (create "eternal"))

  (def prot-fb (decorate (create-oscilating-hello 3)
                         eternal-cache))

  
  (metrics cache))


(comment


  (defn hello [n]
    (str "Hello " n "!!"))

  (def prot (-> hello
                (r/decorate retry
                            {:fallback
                             (fn [n e]
                               (c/get! cache n))
                             :effect
                             (fn [n value]
                               (c/put! cache n value))})
                (tl/decorate timelimitter))))
