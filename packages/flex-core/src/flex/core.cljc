(ns flex.core
  "A library for creating dynamic, incremental dataflow programs in Clojure(Script)."
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

(defn env
  "Creates a new environment, in which all dataflow object state will be
  isolated. See `with-env`.

  Optionally takes a named arg `:scheduler` which computations of the dataflow
  graph will be executed with."
  [& {:keys [scheduler]
      :or {scheduler (async-scheduler/async-scheduler)}}]
  (env/create-env
   {:scheduler scheduler}))


(defmacro with-env
  "Wraps an expression with an environment `env`, isolating all of its changes
  to the dataflow graph to only be seen by other calls wrapped with `env`.
  This includes calls to: `connect!`, `disconnect!`, `watch!`, `send`, and deref"
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


(defn- raise-deref!
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
  "Creates a new stateful dataflow computation that, when connected, updates its
  state by recomputing when any of its dependencies change. See `signal` for the
  much nicer syntax.

  When passed a single function `f`,  will create a computation that runs (f)
  every time its dependencies change.

  When passed a `cutoff?` function (fn [old-state new-state]) in the opts map,
  will only propagate changes to dependents after recomputing when the cutoff
  function returns false.

  When passed two functions `f0` and `f1`, will run (f0) to compute initial
  state and (f1 old-state) on subsequent recomputations."
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
  "Creates a new stateful dataflow computation that, when connected, updates its
  state by recomputing when any of its dependencies change.

  Can be a simple expression based on other dataflow inputs or computations.
  Optionally, you can passe a 0-arity and 1-arity definition, which will call
  the 0-arity expr to compute initial state, then the 1-arity definition with
  the previous state on each subsequent recomputation.

  An opts map may be provided to define the following:
   * :cutoff? - a boolean function (fn [old-state new-state]) that determines
     whether to update dependents after recomputing. Default (constantly false).
   * :on-connect - a side-effecting function (fn [sig]) to run when the
     computation first connects.
   * :on-disconnect - a side-effecting function (fn [sig]) to run when the
     computation disconnects.

  Optionally, a symbol can be provided as a name to allow self-reference in the
  body."
  {:style/indent [:defn]
   :forms '[(signal name? opts? exprs*)
            (signal name? opts? ([] exprs*))
            (signal name?
              opts?
              ([] exprs*)
              ([prev] exprs*))]}
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
  "Same as (def name (signal name ~@body))."
  {:style/indent [:defn]
   :arglists '([name doc-string? opts? body]
               [name doc-string? opts? ([] body)]
               [name doc-string? opts? ([] body) ([prev] body)])}
  [sym & body]
  (let [[docstring opts body] (cond
                                ;; (defsig name {} ,,,)
                                (map? (first body))
                                [nil (assoc (first body) :id sym) (rest body)]

                                ;; (defsig name "docstring" {} ,,,)
                                ;; wrap docstring in a seqable so we can prevent
                                ;; emitting it with ~@ when nil
                                (and (string? (first body)) (map? (second body)))
                                [[(first body)] (second body) (rest (rest body))]

                                ;; (defsig name "docstring" ,,,)
                                (string? (first body))
                                [[(first body)] {:id sym} (rest body)]

                                ;; (defsig name ,,,)
                                :else
                                [nil {:id sym} body])]
    `(def ~sym ~@docstring (signal ~sym ~opts ~@body))))


(defn collect
  "Like `into` for dataflow.

  Takes an initial value, an optional transducer, and a dataflow object `c`.
  Returns a dataflow computation that will `conj` each value propagated from `c`
  with the previous collection."
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
  "Connects a dataflow computation `c` and any of its dependencies. `c` and any
  of its dependencies  will synchronously compute its initial value, and will
  recompute when its dependencies change. Returns `c`."
  [c]
  (doto c (-propagate!)))


(defn disconnect!
  "Disconnects a dataflow computation `c`, clearing its state and ensuring that
  it will not recompute again unless reconnected. Will also disconnect any
  dependencies that have no other dependents. Returns `c`."
  [c]
  (when (satisfies? IComputation c)
    (let [env *environment*
          id (-identify c)
          {:keys [deps computations watches]} (env/relations! env id)]
      (when (or (seq computations) (seq watches))
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
      (env/clear-val! env id)))
  c)


(defn watch!
  "Connects and watches a dataflow object `c`, calling `f` anytime the state
  changes. Returns a 'dispose' function which will remove the watcher and
  disconnect `c` if it has no other dependents. Returns `true` if it disconnects
  `c`."
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
        false))))


(defn connected?
  "Returns true if dataflow computation `c` is currently connected."
  [c]
  (let [env *environment*
        id (-identify c)
        {:keys [deps computations]} (env/relations! env id)]
    (boolean
     (or (satisfies? ISource c) ;; sources are always connected
         (seq deps)
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
  "Creates a memoized computation factory `fc`. `f` is a function that takes any
  arguments and returns a signal `c`. Subsequent calls to the factory will
  immediately return the same `c` until it is disconnected, when it will clear
  the cache and next call to `fc` will re-run `f` to create a new `c`.

  See `signal-fn` for nicer syntax."
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
                   (when (zero? @ref-count)
                     (remove-cache! memo-id args))))]
          (establish-cache! memo-id args c)
          c)))))


(defmacro signal-fn
  "Creates a memoized computation factory. Takes same parameters as
  `clojure.core/fn`. The body of the factory should return a dataflow
  computation such as one created via `signal`.

  The first call to the function returned by this helper will execute the body
  and cache the dataflow computation `c`. Subsequent calls to the factory will
  immediately return the same `c` until it is disconnected, when it will clear
  the cache and next call to `fc` will re-run the factory to create a new `c`."
  {:style/indent [:defn]
   :forms '[(signal-fn name? [params*] exprs*)
            (signal-fn name? ([params*] exprs*) +)]}
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
  "Creates a new dataflow source. `rf` is a reducer function which takes the
  current state and a new message, and returns the new state. (rf) is called to
  compute the initial state."
  ([rf]
   (source {:id (gensym "flex_src")} rf))
  ([{:keys [id]} rf]
   (->IncrementalSource id rf)))


(defn input
  "Creates a new dataflow input with initial state `init`. An input is a source
  which will apply any function and args sent to its current state, similar to
  `clojure.core/swap!`."
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


(defn send
  "Sends a message to `src`, scheduling computation of its state and any
  dependent dataflow computations. Returns a promise which settles when the
  computation has completed, or throws if an error occurs during computation."
  [src & message]
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
                   (-receive src message))
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
