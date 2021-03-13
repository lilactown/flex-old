(ns incrd.core
  (:require
   [clojure.set :as set]
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


(def sentinel `unknown)


(def disconnected `disconnected)


(defn raise-deref!
  [incr]
  (if *deps*
    (do (swap! *deps* conj incr) true)
    false))


(defn- calculate!
  [reaction f]
  (let [env *environment*
        id (-identify reaction)
        v (env/current-val env id sentinel)
        {:keys [deps]} (env/relations env id)

        deps-state (atom #{})
        v' (binding [*deps* deps-state]
             (f v))

        deps' (into #{} (map -identify @deps-state))]
    #_(prn :calculating id deps deps')
    (env/add-ref! *environment* (-identify reaction) reaction)

    ;; add new relations
    (doseq [dep deps']
      (env/add-relation! env dep id))

    ;; remove stale relations
    ;; TODO detect if this is last relation and should disconnect
    (doseq [dep (set/difference deps deps')]
      (env/remove-relation! env dep id))

    ;; set order to be the max of any child's order + 1
    (let [order (->> (for [dep deps']
                       (env/get-order env dep))
                     (apply max)
                     (inc))]
      (env/set-order! env id order))

    ;; set value in context
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
    (let [child-reaction? (raise-deref! this)
          v (env/current-val *environment* identity sentinel)]
      (cond
        (and (not child-reaction?) (= sentinel v))
        disconnected

        ;; connected, not cached
        (= sentinel v)
        (calculate! this #(reducer % (f)))

        ;; connected, cached
        :else
        v))))


(defn reaction
  [f]
  (->IncrementalReaction
   (gensym "incr_reaction")
   (fn [_ v] v)
   f))


(defn connect!
  [r]
  (-propagate! r nil))


(defn disconnect!
  [r]
  (let [env *environment*
        id (-identify r)
        {:keys [deps reactions]} (env/relations env id)]
    (when (seq reactions)
      (throw (ex-info "Cannot disconnect reaction which has dependents"
                      {:reactions reactions})))

    ;; remove all relations
    (doseq [dep deps]
      (env/remove-relation! env dep id))

    ;; remove ref tracker to allow GC of reaction
    (env/clear-ref! env id)

    ;; remove value
    (env/clear-val! env id)))


(defn connected?
  [r]
  (let [env *environment*
        id (-identify r)
        {:keys [deps reactions]} (env/relations env id)]
    (or (seq deps)
        (seq reactions)
        (env/get-ref env id)
        (not= disconnected
              (env/current-val env id disconnected)))))


;;
;; -- Changing sources
;;


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
    (env/add-ref! *environment* (-identify this) this)
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
           #_(prn heap)
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
