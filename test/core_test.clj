(ns core-test
  (:require [resilience4clj-cache.core :as c]
            [clojure.test :refer :all])

  (:import (javax.cache Caching)

           (javax.cache.configuration MutableConfiguration)))

;; mock for an external call
(defn ^:private external-call
  ([n]
   (external-call n nil))
  ([n {:keys [fail? wait]}]
   (when wait
     (Thread/sleep wait))
   (if-not fail?
     (str "Hello " n "!")
     (throw (ex-info "Couldn't say hello" {:extra-info :here})))))

(defn ^:private external-call!
  [a]
  (Thread/sleep 250)
  (swap! a inc))

(deftest create-default-cache
  (let [{:keys [eternal?]} (-> "my-cache"
                               c/create
                               c/config)]
    (is (= true eternal?))))

(deftest create-expire-after-cache
  (let [{:keys [eternal? expire-after]} (-> "my-cache"
                                            (c/create {:expire-after 1500})
                                            c/config)]
    (is (= false eternal?))
    (is (= 1500 expire-after))))

(deftest create-expire-out-of-range
  (try
    (-> "my-cache"
        (c/create {:expire-after 500})
        c/config)
    (catch Throwable t
      (is (= (-> t ex-data :resilience4clj.anomaly/category)
             :resilience4clj.anomaly/invalid-expire-after)))))

(deftest create-custom-factories
  (let [provider (fn [_] (Caching/getCachingProvider))
        manager (fn [provider _] (.getCacheManager provider))
        config (fn [_] (-> (MutableConfiguration.)
                           (.setTypes java.lang.String java.lang.Object)))
        {:keys [eternal?
                expire-after
                provider-fn
                manager-fn
                config-fn] :as bla} (-> "my-cache"
                                        (c/create {:provider-fn provider
                                                   :manager-fn manager
                                                   :config-fn config})
                                        c/config)]
    (is (nil? eternal?))
    (is (nil? expire-after))
    (is (= provider provider-fn))
    (is (= manager manager-fn))
    (is (= config config-fn))))
