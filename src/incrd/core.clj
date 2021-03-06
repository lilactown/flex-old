(ns incrd.core
  (:require
   [incrd.env :as env]
   [incrd.scheduler :as scheduler])
  (:refer-clojure :exclude [send]))



(defprotocol ISource
  (-receive [src x] "Receives a message sent to the source"))


(defprotocol IIncremental
  (-identify [incr] "Returns a unique identifier for the incremental object"))

(def default-env (env/create-env))


(def ^:dynamic *environment* default-env)


(deftype IncrementalSource [identity reducer initial]
  ISource
  (-receive [this x]
    (reducer
     (env/current-val *environment* (-identify this) initial)
     x))

  IIncremental
  (-identify [this] identity)

  clojure.lang.IDeref
  (deref [this]
    (env/current-val *environment* (-identify this) initial)))


(defn- mote-reducer
  [current f]
  (f current))


(defn mote
  [initial]
  (->IncrementalSource
   (gensym "incr_mote")
   mote-reducer
   initial))


(def scheduler (scheduler/future-scheduler))

(defn send [m x]
  (scheduler/schedule
   scheduler
   nil
   (fn []
     (let [env' (env/branch *environment*)
           v (-receive m x)]
       (env/set-val! env' (-identify m) v)
       (if (env/is-parent? *environment* env')
         (env/commit! *environment* env')
         (do (prn :retry)
             (recur)))))))
