(ns resilience4clj-cache.core
  (:import
   (io.github.resilience4j.cache Cache)
   (io.github.resilience4j.core EventConsumer) 
   (io.vavr.control Try)
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

;; informs that a call has been tried, failed and will now be retried
(defmethod event->data :SUCCESS [e]
  (base-event->data e))

;; informs that a call has been retried, but still failed
(defmethod event->data :ERROR [e]
  (base-event->data e))

;; informs that a call has been tried, failed and will now be retried
(defmethod event->data :CACHE [e]
  (base-event->data e))

;; informs that an error has been ignored
(defmethod event->data :IGNORED_ERROR [e]
  (base-event->data e))

(defn ^:private event-consumer [f]
  (reify EventConsumer
    (consumeEvent [this e]
      (println "event-consumer being called")
      (let [data (event->data e)]
        (f data)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create
  [cache]
  (Cache/of cache))

(defn config
  [cache]
  (-> cache
      .getCacheConfig
      cache-config->config-data))

(defn decorate
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

(defn metrics
  [cache-context]
  (let [metrics (.getMetrics cache-context)]
    {:number-of-cache-hits (.getNumberOfCacheHits metrics)
     :number-of-cache-misses (.getNumberOfCacheMisses metrics)}))

(defn listen-event
  [cache event-key f]
  (let [event-publisher (.getEventPublisher cache)
        consumer (event-consumer f)]
    (case event-key
      :success (.onSuccess event-publisher consumer)
      :error (.onError event-publisher consumer)
      :ignored-error (.onIgnoredError event-publisher consumer)
      :cache (.onCache event-publisher consumer))))

(comment
  (def cache (create "my-cache"))
  (config cache)

  (def cache2 (create "other-cache" {:wait-duration 1000
                                     :max-attempts 5}))
  (config cache2)

  ;; mock for an external call
  (defn external-call
    ([n]
     (external-call n nil))
    ([n {:keys [fail? wait]}]
     (println "calling...")
     (when wait
       (Thread/sleep wait))
     (if-not fail?
       (str "Hello " n "!")
       (anomaly! :broken-hello "Couldn't say hello"))))

  (defn random-call
    []
    (let [r (rand)]
      (cond
        (< r 0.4) "I worked!!"
        :else (anomaly! :sorry "Sorry. No cake!"))))
  
  
  (def call (decorate external-call
                      cache2
                      {:fallback (fn [n opts e]
                                   (str "Fallback reply for " n))}))

  (def call2 (decorate random-call
                       cache2
                       {:fallback (fn [e]
                                    (str "Fallback"))}))
  
  (listen-event cache2 :success (fn [e] (println (dissoc e :last-throwable))))
  (listen-event cache2 :error (fn [e] (println (dissoc e :last-throwable))))
  (listen-event cache2 :cache (fn [e] (println (dissoc e :last-throwable))))
  (listen-event cache2 :ignored-error (fn [e] (println (dissoc e :last-throwable))))

  (call "Bla" {:fail? false})

  (call2)

  #_(time (call "bla" {:fail? true}))

  #_(time (try
            (call "bla" {:fail? true})
            (catch Throwable t)))
  (metrics cache2)


  #_(.apply (:wait-duration (config cache))
            (int 1))


  (.apply (reify java.util.function.Function
            (apply [this x]
              (str "Hello " x)))
          "World"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(def cache (.build
            (-> (org.cache2k.Cache2kBuilder/of java.lang.String java.lang.String)
                (.name "my-cache2")
                (.eternal true)
                (.entryCapacity 100))))

(.put cache "A" "My a")

(.containsKey cache "A")

(.peek cache "A")

#_(org.cache2k.provider.CachingProvider
   )


#_(new org.cache2k.jcache.provider.JCacheAdapter
       (new org.cache2k.jcache.provider.JCacheManagerAdapter))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



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
(.getNumberOfCacheMisses (.getMetrics cache-wrapper))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def cache (cache2k/create "my-cache"
                           {:key-class java.lang.String
                            :val-class java.lang.String
                            :eternal true
                            :entry-capacity 100}))

(def cache-context (create cache-context))

(defn ext-call [n]
  (str "Hello " n "!"))

(def decorated-cache-call (decorate ext-call cache-context))

(decorated-cache-call "Tiago")

(def decorated-cache-call (decorate ext-call cache-context
                                    {:fallback (fn [n e] "Fallback")
                                     :cache-key (fn [n] (.hashCode n))}))

(config cache)
(metrics cache)
(listen-event :cache-hit (fn [e] e))
(listen-event :cache-miss (fn [e] e))
(listen-event :cache-error (fn [e] e))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



(require '[resilience4clj-cache.cache2k :as cache2k])

(def cache (cache2k/create "my-cache2" {:eternal true
                                        :entry-capacity 2}))

(def cache-context (create cache))

(defn ext-call [n]
  (Thread/sleep 2000)
  (str "Hello " n "!"))

(defn ext-call2 [n i]
  (Thread/sleep 1000)
  {:name n
   :val i
   :rand (rand-int i)})

(def decorated-cache-call (decorate ext-call cache-context))

(def decorated-cache-call2 (decorate ext-call2 cache-context))

(decorated-cache-call "Tiago")

(decorated-cache-call2 "Tiago" 6001)

(dotimes [n 100]
  (decorated-cache-call "Tiago2"))

(metrics cache-context)
