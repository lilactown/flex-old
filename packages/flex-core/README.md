# flex

Flex is a library for creating dynamic, incremental dataflow programs in
Clojure(Script).

> Weird flex, but ok

Work in progress. DO NOT EAT!

## Terminology:

* Incremental dataflow: whenever a piece of data changes, attempt to save time
  by only recomputing that which depend on the changed data

* Source: a state container which can change its state when it receives messages
  (any kind of value)

* Computation: a state container with a function that will be used to compute
  its state based on sources or other computations. The function may be called
  automatically anytime a dependency changes to update its state.

* Dataflow object: either a source or a computation.

* Dataflow graph: a collection of dataflow objects w/ relations to one another.

* Connecting: when a computation is first constructed, it is \"disconnected\".
  This means it will not automatically compute its state. Once \"connected\",
  it will synchronously compute its stay and may be recomputed automatically
  based on changes to its dependencies.

* Environment: a scope which dataflow graphs can be connected, sent messages
  and recomputed in isolation from other environments.

## TODO 

* [x] sources can be created via `input` and `source`
* [x] Messages can be sent to sources via `flex.core/send` to update them.
* [x] Computations can be created via `signal` and `defsig`
* [x] Aggregations of values over time can be created via `collect`
* [x] Computations can be cutoff based on some function
* [x] Memoized signal factories can be created via `signal-fn` and `defsig-fn`
* [x] Computations are done in topological order, avoiding glitches
* [x] Error handling: bails recomputing on error and does not update any variables
* [x] Error handling: communicate error to sender
* [x] Reified environments avoid global state
* [x] Changes can be observed and trigger effects on change
* [ ] Computations can trigger side effects in the body
* [x] Closure to create some shared state on connect?
* [ ] Spec / validation?


Scheduling

* [x] Asynchronous scheduling of computations
* [ ] Parallelizing computations of the same height
* [ ] Prioritizing computation based on message
* [ ] Time slicing ~16ms
* [ ] Batching
* [ ] Benchmarks
* [ ] Tracing

## API

```clojure
(require '[flex.core :as f])

(def counter (f/input 0))

@(f/send counter inc) ;; blocks until re-calculation based on message is completed (JVM only)
@counter ;; => 1

;; create a calculation based on counter
(def counter*2 (f/signal (* 2 @counter)))
(def dispose! (f/watch! counter*2 prn))

@counter*2 ;; => 2

@(f/send counter inc)
;; Prints: 4

@counter ;; => 2
@counter*2 ;; => 4

(dispose!) ;; disconnects counter*2, cleans up listener
```
