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
    (do (swap! *deps* conj incr) true)
    false))


(defn calculate!
  [reaction f]
  (let [env *environment*
        id (-identify reaction)
        v (env/current-val env id sentinel)
        deps' (atom #{})
        v' (binding [*deps* deps']
             (f v))]
    (prn :calculating id)
    (doseq [dep @deps']
      (env/add-relation! env (-identify dep) id))
    ;; TODO remove relations
    (let [order (->> (for [dep @deps']
                       (env/get-order env (-identify dep)))
                     (apply max)
                     (inc))]
      (env/set-order! env id order))
    (env/set-val! env (-identify reaction) v')
    v'))


(deftype IncrementalReaction [identity reducer f]
  IReaction
  (-propagate! [this _dep]
    ;; recalculate
    (calculate! this #(reducer % (f))))

  IIncremental
  (-identify [this] identity)

  clojure.lang.IDeref
  (deref [this]
    (raise-deref! this)
    (let [v (env/current-val *environment* identity sentinel)]
      (if (= sentinel v)
        ;; not cached
        (calculate! this #(reducer % (f)))
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


(defn- into-heap
  ([order+reactions]
   (into-heap (sorted-map) order+reactions))
  ([heap order+reactions]
   (reduce
    (fn [m [order reaction]]
      (update
       m
       order
       (fnil conj #{})
       reaction))
    heap
    order+reactions)))


(defn send [src x]
  (let [env *environment*]
    (scheduler/schedule
     scheduler
     nil
     (fn []
       (let [env' (env/branch env)
             v (-receive src x)
             id (-identify src)
             {:keys [reactions]} (env/relations env' id)]
         (env/set-val! env' id v)
         (loop [heap (into-heap (map (fn [rid]
                                       [(env/get-order env' rid)
                                        (env/get-ref env' rid)])
                                     reactions))]
           (prn heap)
           (when-let [[order reactions] (first heap)]
             (when-let [reaction (first reactions)]
               (binding [*environment* env']
                 (-propagate! reaction src))
               (let [{:keys [reactions]} (env/relations env' (-identify reaction))
                     heap' (-> heap
                               ;; remove reaction from the heap
                               (update order disj reaction)
                               ;; add new reactions to the heap
                               (into-heap
                                (map (fn [rid]
                                       [(env/get-order env' rid)
                                        (env/get-ref env' rid)])
                                     reactions)))]
                 (recur
                  (if (zero? (count (get heap' order)))
                    ;; no reactions left in this order, dissoc it so that the
                    ;; lowest order is always first
                    (dissoc heap' order)
                    heap'))))))
         (if (env/is-parent? *environment* env')
           (env/commit! *environment* env')
           (do (prn :retry)
               (recur))))))))


;;
;; -- environments
;;

(defn env []
  (env/create-env))


(defmacro with-env
  [env & body]
  `(binding [*environment* ~env]
     ~@body))
