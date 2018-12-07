(ns resilience4clj-cache.cache2k
  (:refer-clojure :exclude [load])
  (:import (org.cache2k Cache2kBuilder)
           (org.cache2k.configuration Cache2kConfiguration)
           (org.cache2k.integration CacheLoader
                                    FunctionalCacheLoader
                                    AdvancedCacheLoader)
           (org.cache2k.jcache ExtendedMutableConfiguration)

           (java.util.concurrent TimeUnit)

           (javax.cache Caching)))

(defn loader [f]
  (reify FunctionalCacheLoader
    (load [_ k]
      (let [data (f k)]
        (println "will return" data "for key" k)
        data)
      "Juro q estou tentando")))


(defn create
  ([n]
   (create n nil))
  ([n {:keys [key-class val-class
              eternal? entry-capacity keep-data-after-expired? suppress-exceptions?
              expire-after-write loader refresh-ahead? sharp-expiry? loader-thread-count
              retry-interval max-retry-interval resilience-duration permit-null-values?
              disable-statistics? boost-concurrency? enable-jmx?]
       :or {key-class java.lang.Integer
            val-class java.lang.Object}
       :as opts}]
   (let [cache-manager (-> "org.cache2k.jcache.provider.JCacheProvider"
                           Caching/getCachingProvider
                           .getCacheManager)]
     (-> cache-manager
         (.createCache n (ExtendedMutableConfiguration/of
                          (cond-> (Cache2kBuilder/of key-class val-class)
                            (not (nil? eternal?)) (.eternal eternal?)

                            entry-capacity (.entryCapacity entry-capacity)

                            (not (nil? keep-data-after-expired?))
                            (.keepDataAfterExpired keep-data-after-expired?)

                            (not (nil? suppress-exceptions?))
                            (.suppressExceptions suppress-exceptions?)

                            expire-after-write
                            (.expireAfterWrite expire-after-write TimeUnit/MILLISECONDS)

                            loader
                            (.loader ^FunctionalCacheLoader loader)

                            (not (nil? refresh-ahead?))
                            (.refreshAhead refresh-ahead?)

                            (not (nil? sharp-expiry?))
                            (.sharpExpiry sharp-expiry?)

                            loader-thread-count
                            (.loaderThreadCount loader-thread-count)

                            retry-interval
                            (.retryInterval retry-interval TimeUnit/MILLISECONDS)

                            max-retry-interval
                            (.maxRetryInterval max-retry-interval TimeUnit/MILLISECONDS)

                            resilience-duration
                            (.resilienceDuration resilience-duration TimeUnit/MILLISECONDS)

                            (not (nil? permit-null-values?))
                            (.permitNullValues permit-null-values?)

                            (not (nil? disable-statistics?))
                            (.disableStatistics disable-statistics?)

                            (not (nil? boost-concurrency?))
                            (.boostConcurrency boost-concurrency?)

                            (not (nil? enable-jmx?))
                            (.enableJmx enable-jmx?))))))))
