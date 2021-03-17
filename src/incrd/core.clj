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


(defprotocol IComputation
  (-propagate! [computation]))


(def default-env (env/create-env))


(def ^:dynamic *environment* default-env)


(def ^:dynamic *deps* nil)


(def none `none)


(def disconnected `disconnected)


(defn raise-deref!
  [incr]
  (if *deps*
    (do (swap! *deps* conj incr) true)
    false))


(declare disconnect!)


(defn- calculate!
  [computation rf input-fn cutoff? initial]
  (let [env *environment*
        id (-identify computation)
        v (env/current-val env id initial)
        {:keys [deps]} (env/relations env id)

        deps-state (atom #{})
        input (binding [*deps* deps-state]
                (input-fn))

        ;; TODO can we optimize when `rf` returns a reduced?
        v' (unreduced (rf v input))
        deps' (into #{} (map -identify @deps-state))]
    (env/add-ref! *environment* id computation)

    ;; add new relations
    (doseq [dep deps']
      (env/add-relation! env dep id))

    ;; remove stale relations
    (doseq [dep (set/difference deps deps')]
      (env/remove-relation! env dep id)
      (let [{:keys [computations]} (env/relations env dep)]
        (when (empty? computations)
          (disconnect! (env/get-ref env dep)))))

    ;; set order to be the max of any child's order + 1
    (let [order (->> (for [dep deps']
                       (env/get-order env dep))
                     ;; 0 here for when a computation has no deps, we don't
                     ;; call `max` with no args
                     (apply max 0)
                     (inc))]
      (env/set-order! env id order))

    ;; set value in context
    (env/set-val! env id v')

    [v' (if (and (some? cutoff?) (cutoff? v v'))
          #{}
          (:computations (env/relations env id)))]))


(deftype IncrementalComputation [id reducer input-fn cutoff? initial]
  IComputation
  (-propagate! [this]
    ;; recalculate
    (calculate! this reducer input-fn cutoff? initial))

  IIncremental
  (-identify [this] id)

  clojure.lang.IDeref
  (deref [this]
    (let [child-computation? (raise-deref! this)
          v (env/current-val *environment* id none)]
      (cond
        ;; connecting, not cached
        (and child-computation? (= none v))
        (let [[v] (calculate! this reducer input-fn cutoff? initial)]
          (if (= none v)
            (throw (ex-info "Computation does not have a value" {::id id
                                                                 ::value none}))
            v))

        ;; connected, cached
        :else
        v))))


(defn- computation-rf
  [_ v] v)


(defn signal
  ([f]
   (signal {} f))
  ([{:keys [id cutoff?]} f]
   (->IncrementalComputation
    (or id (gensym "incr_computation"))
    computation-rf
    f
    cutoff?
    none)))


(defmacro defsig
  [sym & body]
  (let [[opts body] (if (map? (first body))
                      [(first body) (rest body)]
                      [{} body])]
    `(def ~sym
       (signal
        ~opts
        (fn []
          ~@body)))))


(defn collect
  ([initial c]
   (->IncrementalComputation
    (gensym "incr_collect")
    (fn [coll c]
      (conj coll c))
    #(deref c)
    nil
    initial))
  ([initial xform c]
   (->IncrementalComputation
    (gensym "incr_collect")
    (xform (fn [coll c]
             (conj coll c)))
    #(deref c)
    nil
    initial)))


(defn connect!
  [r]
  (first (-propagate! r)))


(defn disconnect!
  [r]
  (let [env *environment*
        id (-identify r)
        {:keys [deps computations]} (env/relations env id)]
    (when (seq computations)
      (throw (ex-info "Cannot disconnect computation which has dependents"
                      {:computations computations})))

    ;; remove all relations
    (doseq [dep deps]
      (env/remove-relation! env dep id)
      (let [{:keys [computations]} (env/relations env dep)]
        (when (empty? computations)
          (disconnect! (env/get-ref env dep)))))

    ;; remove ref tracker to allow GC of computation
    (env/clear-ref! env id)

    ;; remove value
    (env/clear-val! env id)))


(defn connected?
  [r]
  (let [env *environment*
        id (-identify r)
        {:keys [deps computations]} (env/relations env id)]
    (boolean
     (or (seq deps)
         (seq computations)
         (env/get-ref env id)))))


;;
;; -- Sources
;;


(deftype IncrementalSource [id reducer initial]
  ISource
  (-receive [this x]
    (reducer
     (env/current-val *environment* id initial)
     x))

  IIncremental
  (-identify [this] id)

  clojure.lang.IDeref
  (deref [this]
    (raise-deref! this)
    (env/add-ref! *environment* id this)
    (let [v (env/current-val *environment* id none)]
      (if (= none v)
        (do (env/set-val! *environment* id initial)
            initial)
        v))))


(defn- input-reducer
  [current x]
  (if (vector? x)
    (apply (first x) current (rest x))
    (x current)))


(defn input
  [initial]
  (->IncrementalSource
   (gensym "incr_input")
   input-reducer
   initial))


(def scheduler (scheduler/future-scheduler))


(defn- into-heap
  ([order+computations]
   (into-heap (sorted-map) order+computations))
  ([heap order+computations]
   (reduce
    (fn [m [order computation]]
      (update
       m
       order
       (fnil conj #{})
       computation))
    heap
    order+computations)))


(defn send [src x & args]
  (let [env *environment*]
    (scheduler/schedule
     scheduler
     nil
     (fn []
       (let [env' (env/branch env)
             id (-identify src)
             v (env/current-val env' id none)
             v' (if (seq args)
                  (-receive src (into [x] args))
                  (-receive src x))]
         (when-not (identical? v v')
           (env/set-val! env' id v')
           (loop [heap (into-heap (map (fn [rid]
                                         [(env/get-order env' rid)
                                          (env/get-ref env' rid)])
                                       (:computations (env/relations env' id))))]
             (when-let [[order computations] (first heap)]
               (when-let [computation (first computations)]
                 (let [rid (-identify computation)
                       ;; this should never be `none`
                       old (env/current-val env' rid none)
                       [new computations] (binding [*environment* env']
                                            (-propagate! computation))
                       heap' (cond-> heap
                               ;; remove computation from heap
                               true (update order disj computation)

                               (not= old new)
                               ;; add new computations to the heap
                               (into-heap
                                (map (fn [rid]
                                       [(env/get-order env' rid)
                                        (env/get-ref env' rid)])
                                     computations)))]
                   (recur
                    (if (zero? (count (get heap' order)))
                      ;; no computations left in this order, dissoc it so that the
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
