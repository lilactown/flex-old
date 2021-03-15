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


(def unknown `unknown)


(def disconnected `disconnected)


(defn raise-deref!
  [incr]
  (if *deps*
    (do (swap! *deps* conj incr) true)
    false))


(defn- calculate!
  [reaction rf f]
  (let [env *environment*
        id (-identify reaction)
        v (env/current-val env id unknown)
        {:keys [deps]} (env/relations env id)

        deps-state (atom #{})
        input (binding [*deps* deps-state]
             (f))

        v' (if (= unknown v)
             (rf input)
             (rf v input))

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
    (calculate! this reducer f))

  IIncremental
  (-identify [this] identity)

  clojure.lang.IDeref
  (deref [this]
    (let [child-reaction? (raise-deref! this)
          v (env/current-val *environment* identity unknown)]
      (cond
        (and (not child-reaction?) (= unknown v))
        disconnected

        ;; connected, not cached
        (= unknown v)
        (calculate! this reducer f)

        ;; connected, cached
        :else
        v))))


(defn- reaction-rf
  ([v] v)
  ([_ v] v))


(defn reaction
  ([f]
   (->IncrementalReaction
    (gensym "incr_reaction")
    reaction-rf
    f))
  ([xform f]
   (->IncrementalReaction
    (gensym "incr_reaction")
    (xform reaction-rf)
    f)))


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
    (boolean
     (or (seq deps)
         (seq reactions)
         (env/get-ref env id)
         (not= disconnected
               (env/current-val env id disconnected))))))


;;
;; -- Sources
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
    (env/add-ref! *environment* identity this)
    (let [v (env/current-val *environment* identity unknown)]
      (if (= unknown v)
        (do (env/set-val! *environment* identity initial)
            initial)
        v))))


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
             id (-identify src)
             v (env/current-val env' id unknown)
             v' (-receive src x)]
         (when-not (identical? v v')
           (env/set-val! env' id v')
           (loop [heap (into-heap (map (fn [rid]
                                       [(env/get-order env' rid)
                                        (env/get-ref env' rid)])
                                     (:reactions (env/relations env' id))))]
           (when-let [[order reactions] (first heap)]
             (when-let [reaction (first reactions)]
               (let [rid (-identify reaction)
                     ;; this should never be `unknown`
                     old (env/current-val env' rid unknown)
                     new (binding [*environment* env']
                           (-propagate! reaction src))
                     {:keys [reactions]} (env/relations env' rid)
                     heap' (cond-> heap
                             ;; add new reactions to the heap
                             true (update order disj reaction)

                             (not= old new)
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
                    heap')))))))

         ;; another commit has happened between now and when we started
         ;; propagating; restart
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
