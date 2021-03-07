(ns incrd.core
  (:require
   [incrd.env :as env]
   [incrd.scheduler :as scheduler])
  (:refer-clojure :exclude [send]))



(defprotocol ISource
  (-receive [src x] "Receives a message sent to the source"))


(defprotocol IIncremental
  (-identify [incr] "Returns a unique identifier for the incremental object"))


(defprotocol IReaction
  (-propagate! [reaction dep]))

(def default-env (env/create-env))


(def ^:dynamic *environment* default-env)


(def ^:dynamic *deps* nil)


(def sentinel ::unknown)


(defn raise-deref!
  [incr]
  (env/add-ref! *environment* (-identify incr) incr)
  (if *deps*
    (do (prn :deps) (swap! *deps* conj incr) true)
    false))


(defn connect!
  [reaction f]
  (let [env *environment*
        id (-identify reaction)
        v (env/current-val *environment* id sentinel)
        deps' (atom #{})
        v' (binding [*deps* deps']
             (f v))]
    (doseq [dep @deps']
      (env/add-relation! env (-identify dep) id))
    (let [order (->> (for [dep @deps']
                       (env/get-order env (-identify dep)))
                     (apply max)
                     (inc))]
      (env/set-order! env id order))
    (prn (env/relations env id))
    (env/set-val! env (-identify reaction) v')
    v'))


(deftype IncrementalReaction [identity reducer f]
  IReaction
  (-propagate! [this _dep]
    ;; recalculate
    (connect! this #(reducer % (f))))

  IIncremental
  (-identify [this] identity)

  clojure.lang.IDeref
  (deref [this]
    (raise-deref! this)
    (let [v (env/current-val *environment* identity sentinel)]
      (if (= sentinel v)
        ;; not cached
        (connect! this #(reducer % (f)))
        v))))


(deftype IncrementalSource [identity reducer initial]
  ISource
  (-receive [this x]
    (reducer
     (env/current-val *environment* identity initial)
     x))

  IIncremental
  (-identify [this] identity)

  clojure.lang.IDeref
  (deref [this]
    (raise-deref! this)
    (env/current-val *environment* identity initial)))


(defn- mote-reducer
  [current f]
  (f current))


(defn mote
  [initial]
  (->IncrementalSource
   (gensym "incr_mote")
   mote-reducer
   initial))


(defn reaction
  [f]
  (->IncrementalReaction
   (gensym "incr_reaction")
   (fn [_ v] v)
   f))


(def scheduler (scheduler/future-scheduler))

(defn send [src x]
  (scheduler/schedule
   scheduler
   nil
   (fn []
     (let [env' (env/branch *environment*)
           v (-receive src x)
           id (-identify src)
           {:keys [reactions]} (env/relations env' id)]
       (env/set-val! env' id v)
       (doseq [rid reactions]
         (prn (env/get-order env' rid))
         (binding [*environment* env']
           (-propagate! (env/get-ref env' rid) src)))
       (if (env/is-parent? *environment* env')
         (env/commit! *environment* env')
         (do (prn :retry)
             (recur)))))))
