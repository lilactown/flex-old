# flex

Flex is a library for creating dynamic, incremental dataflow programs in
Clojure(Script).

> Weird flex, but ok

Work in progress. DO NOT EAT!

## TODO 

* [x] Data sources can be created via `input` and `source`
* [x] Messages can be sent to sources via `flex.core/send` to update them.
* [x] Computations can be created via `signal` and `defsig`
* [x] Aggregations of values over time can be created via `collect`
* [x] Computations can be cutoff based on some function
* [ ] Memoized signal factories can be created via `signal-fn` and `defsig-fn`
* [x] Computations are done in topological order, avoiding glitches
* [x] Error handling: bails recomputing on error and does not update any variables
* [x] Error handling: communicate error to sender
* [x] Reified environments avoid global state
* [x] Changes can be observed and trigger effects on change
* [ ] Computations can trigger side effects in the body
* [ ] Closure to define initial state


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

(def counter*2 (f/signal (* 2 counter)))
(def dispose! (f/watch! counter*2 prn))

@counter*2 ;; => 2

@(f/send counter inc)
;; Prints: 4

@counter ;; => 2
@counter*2 ;; => 4

(dispose!) ;; disconnects counter*2, cleans up listener
```
