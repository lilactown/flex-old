(ns flex.core
  (:require
   [clojure.set :as set]
   [flex.env :as env]
   [flex.scheduler :as scheduler]
   [flex.async-scheduler :as async-scheduler])
  #?(:clj (:refer-clojure :exclude [send])
     :cljs (:require-macros [flex.core])))



(defprotocol ISource
  (-receive [src x] "Receives a message sent to the source"))


(defprotocol IIncremental
  (-identify [incr] "Returns a unique identifier for the incremental object"))


(defprotocol IComputation
  (-propagate! [computation]
    "Compute and return new value and whether to update dependents")
  (-on-connect! [c])
  (-on-disconnect! [c]))


;;
;; -- environments
;;

(defn env [& {:keys [scheduler]
              :or {scheduler (async-scheduler/async-scheduler)}}]
  (env/create-env
   {:scheduler scheduler}))


(defmacro with-env
  [env & body]
  `(binding [*environment* ~env]
     ~@body))


(def default-env (env))


(def ^:dynamic *environment* default-env)


;;
;; -- computations
;;


(def ^:dynamic *deps* nil)


(def none `none)


(defn raise-deref!
  [c]
  (if *deps*
    (do (swap! *deps* conj c) true)
    false))


(declare disconnect!)


(defn- calculate!
  [computation input-fn cutoff?]
  (let [env *environment*
        id (-identify computation)
        v (env/current-val env id none)
        {:keys [deps]} (env/relations! env id)

        deps-state (atom #{})
        ;; TODO can we optimize when `rf` returns a reduced?
        v' (binding [*deps* deps-state]
             (unreduced (if (= none v)
                          (input-fn)
                          (input-fn v))))

        deps' (into #{} (map -identify @deps-state))]
    (let [ref (env/get-ref env id)]
      (when (nil? ref)
        ;; we're connecting for the first time
        (-on-connect! computation))
      (env/add-ref! env id computation))

    ;; add new relations
    (doseq [dep deps']
      (env/add-relation! env dep id))

    ;; remove stale relations
    (doseq [dep (set/difference deps deps')]
      (env/remove-relation! env dep id)
      (let [{:keys [computations]} (env/relations! env dep)]
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

    [v' (and (some? cutoff?) (cutoff? v v'))]))


(deftype IncrementalComputation [id input-fn cutoff? on-connect on-disconnect]
  IComputation
  (-propagate! [this]
    ;; recalculate
    (calculate! this input-fn cutoff?))
  (-on-connect! [this]
    (when (some? on-connect)
      (on-connect this)))
  (-on-disconnect! [this]
    (when (some? on-disconnect)
      (on-disconnect this)))

  IIncremental
  (-identify [this] id)

  #?(:clj clojure.lang.IDeref
     :cljs IDeref)
  (#?(:clj deref
      :cljs -deref) [this]
    (let [child-computation? (raise-deref! this)
          v (env/current-val *environment* id none)]
      (cond
        ;; connecting, not cached
        (and child-computation? (= none v))
        (let [[v] (calculate! this input-fn cutoff?)]
          (if (= none v)
            (throw (ex-info "Computation does not have a value" {::id id
                                                                 ::value none}))
            v))

        ;; connected, cached
        :else
        v))))


(defn create-signal
  ([f]
   (create-signal {} f))
  ([{:keys [id cutoff? on-disconnect]} f]
   (->IncrementalComputation
    (or id (gensym "incr_computation"))
    (fn input-fn
      ([] (f))
      ([_prev] (f)))
    cutoff?
    nil on-disconnect))
  ([{:keys [id cutoff? on-disconnect] :as opts} f0 f1]
   (if (some? f1)
     (->IncrementalComputation
      (or id (gensym "incr_computation"))
      (fn input-fn
        ([] (f0))
        ([prev] (f1 prev)))
      cutoff?
      nil on-disconnect)
     ;; sometimes f1 gets passed as nil; just vibe with it
     (create-signal opts f0))))


(defn- signal-name?
  [x]
  (and (symbol? x) (not= "_" (name x))))


(defmacro signal
  {:style/indent [:defn]}
  [& body]
  (let [[id opts body] (let [[hd snd & tail] body]
                         (cond
                           ;; (signal name {} ,,,)
                           (and (signal-name? hd) (map? snd))
                           [hd (assoc snd :id (list 'quote hd)) tail]

                           ;; (signal name ,,,)
                           (signal-name? hd)
                           [hd {:id (list 'quote hd)} (cons snd tail)]

                           ;; (signal {} ,,,)
                           (map? hd)
                           (let [id (gensym "flex_computation")]
                             [id (assoc hd :id (list 'quote id)) (cons snd tail)])

                           ;; (signal ,,,)
                           :else
                           (let [id (gensym "flex_computation")]
                             [id {:id (list 'quote id)} (cons
                                                         hd
                                                         (when snd
                                                           (cons snd tail)))])))
        multi-arity? (and (list? (first body))
                          (vector? (ffirst body))
                          (empty? (ffirst body)))]
    (if multi-arity?
      (let [fn-expr `(fn ~id ~@body)
            f (gensym)]
        `(let [~f ~fn-expr]
           (create-signal
            ~opts
            (fn [] (~f))
            ;; use ~@ to not emit if nil
            ~@(when (some? (second body))
                `((fn [prev#] (~f prev#)))))))
      `(create-signal
        ~opts
        (fn [] ~@body)))))


(defmacro defsig
  {:style/indent [:defn]}
  [sym & body]
  (let [[opts body] (if (map? (first body))
                      [(assoc (first body) :id sym) (rest body)]
                      [{:id sym} body])]
    `(def ~sym (signal ~sym ~opts ~@body))))


(defn collect
  ([init c]
   (->IncrementalComputation
    (gensym (str (-identify c) "_collect"))
    (fn
      ([] (conj init (deref c)))
      ([coll] (conj coll (deref c))))
    nil nil nil))
  ([init xform c]
   (->IncrementalComputation
    (gensym (str (-identify c) "_collect"))
    (let [rf (xform (fn rf [coll x] (conj coll x)))]
      (fn input-fn
        ([] (rf init @c))
        ([prev] (rf prev @c))))
    nil nil nil)))


(defn connect!
  [c]
  (doto c (-propagate!)))


(defn disconnect!
  [c]
  (when (satisfies? IComputation c)
    (let [env *environment*
          id (-identify c)
          {:keys [deps computations]} (env/relations! env id)]
      (when (seq computations)
        (throw (ex-info "Cannot disconnect computation which has dependents"
                        {:computations computations})))

      ;; run side effects first
      (-on-disconnect! c)
      ;; remove all relations
      (doseq [dep deps]
        (env/remove-relation! env dep id)
        (let [{:keys [computations]} (env/relations! env dep)]
          (when (empty? computations)
            (disconnect! (env/get-ref env dep)))))

      ;; remove ref tracker to allow GC of computation
      (env/clear-ref! env id)

      ;; remove value if not a source
      (env/clear-val! env id))))


(defn watch!
  [c f]
  ;; lexically bind *environment* so that we properly close over it in
  ;; the dispose fn
  (let [env *environment*
        id (-identify c)
        computation? (satisfies? IComputation c)]
    (env/add-watcher! env (-identify c) f)
    (when computation?
      (connect! c))
    (fn dispose! []
      (env/remove-watcher! env (-identify c) f)
      ;; TODO don't use exceptions for this!!
      (if computation?
        (try
          (disconnect! c)
          true
          (catch #?(:clj Exception
                    :cljs js/Error) e
            false))
        true))))


(defn connected?
  [r]
  (let [env *environment*
        id (-identify r)
        {:keys [deps computations]} (env/relations! env id)]
    (boolean
     (or (seq deps)
         (seq computations)
         (env/get-ref env id)))))


;;
;; -- memoized computations
;;


;; TODO explore core.memoize
;; this may not handle concurrency as well as we'd like
(def ^:private compute-cache (atom {}))


(defn- establish-cache!
  [id args c]
  (swap! compute-cache assoc-in [id args] c))


(defn- lookup-cache
  [id args]
  (get-in @compute-cache [id args]))


(defn- remove-cache!
  [id args]
  (swap! compute-cache
         (fn [cache]
           (let [cache' (update cache id dissoc args)]
             ;; remove id from cache completely if no other args present
             (if (empty? (get cache' id))
               (dissoc cache' id)
               cache')))))


(defn create-signal-fn
  [f]
  (let [memo-id (gensym "flex_comp_fn")]
    (fn [& args]
      (if-some [c (lookup-cache memo-id args)]
        c
        (let [inner-c (apply f args)
              ref-count (atom 0)
              c (->IncrementalComputation
                 (gensym memo-id)
                 (fn
                   ([] @inner-c)
                   ([_] @inner-c))
                 nil
                 ;; on-connect
                 (fn [_] (swap! ref-count inc))
                 ;; on-disconnect
                 (fn [_]
                   (swap! ref-count dec)
                   (when (zero? (doto @ref-count prn))
                     (remove-cache! memo-id args))))]
          (establish-cache! memo-id args c)
          c)))))


(defmacro signal-fn
  {:style/indent [:defn]}
  [& body]
  `(create-signal-fn
    (fn ~@body)))


;;
;; -- sources
;;


;; TODO sources should clear themselves from any envs on GC... how?
(deftype IncrementalSource [id reducer]
  ISource
  (-receive [this x]
    (let [v (env/current-val *environment* id none)]
      (reducer
       (if (= v none)
         (reducer)
         v)
       x)))

  IIncremental
  (-identify [this] id)

  #?(:clj clojure.lang.IDeref
     :cljs IDeref)
  (#?(:clj deref
      :cljs -deref) [this]
    (raise-deref! this)
    (env/add-ref! *environment* id this)
    (let [v (env/current-val *environment* id none)]
      (if (= none v)
        (env/set-val! *environment* id (reducer))
        v))))


(defn source
  ([rf]
   (source {:id (gensym "flex_src")} rf))
  ([{:keys [id]} rf]
   (->IncrementalSource id rf)))


(defn input
  ([init] (input {:id (gensym "flex_input")} init))
  ([{:keys [id]} init]
   (->IncrementalSource
    id
    (fn
      ([] init)
      ([current x]
       (apply (first x) current (rest x)))))))


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
  (let [env *environment*
        {:keys [scheduler]} @env
        retries (atom 0)]
    (scheduler/schedule
     scheduler
     nil
     (fn stabilize!
       ([]
        (let [env' (env/branch env)
              id (-identify src)
              v (env/current-val env' id none)
              v' (binding [*environment* env']
                   (-receive src (into [x] args)))
              {:keys [computations watches]} (env/relations! env' id)
              heap (into-heap
                    (map
                     (fn [cid]
                       [(env/get-order env' cid)
                        (env/get-ref env' cid)])
                     computations))
              fx (into #{} (map (fn [f] #(f v'))) watches)]
          (when-not (identical? v v')
            (env/set-val! env' id v')
            [env' heap fx])))
       ([env' heap fx]
        (if-let [[order computations] (first heap)]
          (let [computation (first computations)
                rid (-identify computation)
                ;; this should never be `none`
                v (env/current-val env' rid none)
                [v' cutoff?] (binding [*environment* env']
                               (-propagate! computation))
                {:keys [computations watches]} (env/relations! env' rid)
                heap' (cond-> heap
                        ;; remove computation from heap
                        true (update order disj computation)

                        (and (not cutoff?) (not= v v'))
                        ;; add new computations to the heap
                        (into-heap
                         (map (fn [rid]
                                [(env/get-order env' rid)
                                 (env/get-ref env' rid)])
                              computations)))]
            (vector
             env'
             (if (zero? (count (get heap' order)))
               ;; no computations left in this order, dissoc it so that the
               ;; lowest order is always first
               (dissoc heap' order)
               heap')
             (if cutoff?
               fx
               (into fx (map (fn [f] #(f v'))) watches))))
          ;; wrap it up
          (if (env/is-parent? env env')
            (do (env/commit! env env')
                (doseq [f fx]
                  (f))
                nil)
            ;; another commit has happened between now and when we started
            ;; propagating; restart
            (when (< @retries 10)
              (swap! retries inc)
              (prn :retry)
              (stabilize!)))))))))
