(ns incrd.core
  (:refer-clojure :exclude [send]))


(defprotocol IEnvironment
  (-current-val [env incr initial]))


(defprotocol ISource
  (-receive [src x] "Receives a message sent to the source"))


(defprotocol IIncremental
  (-identify [incr] "Returns a unique identifier for the incremental object"))


(defrecord Environment [incr-values incr-graph]
  IEnvironment
  (-current-val [this incr initial]
    (get @incr-values (-identify incr) initial)))


(def default-env (->Environment (atom {}) (atom {})))


(def ^:dynamic *environment* default-env)


(deftype IncrementalSource [identity reducer initial]
  ISource
  (-receive [this x]
    (reducer
     (-current-val *environment* this initial)
     x))

  IIncremental
  (-identify [this] identity)

  clojure.lang.IDeref
  (deref [this]
    (-current-val *environment* this initial)))


(defn- mote-reducer
  [current f]
  (f current))


(defn mote
  [initial]
  (->IncrementalSource
   (gensym "incr_mote")
   mote-reducer
   initial))


(defn send [m x]
  (future
    (Thread/sleep 100)
    (let [v (-receive m x)]
      (swap! (:incr-values *environment*)
             assoc
             (-identify m)
             v))))
