# flex

# flex

> Weird flex, but ok

flex is a library for creating dynamic, incremental computations in 
Clojure(Script).

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
* [ ] Error handling: communicate error to sender (I broke it)
* [x] Reified environments avoid global state
* [x] Asynchronous scheduling of computations, splitting work
* [ ] Parallelizing computations of the same height
* [ ] Prioritizing computation based on message
* [ ] Batching
* [x] Changes can be observed and trigger effects on change
* [ ] Computations can trigger side effects in the body
* [ ] Benchmarks

## API

```clojure
(require '[flex.core :as f])

(def counter (f/input 0))

@(f/send counter inc) ;; => nil
@counter ;; => 1

(def counter*2 (f/signal #(* 2 counter)))
(def dispose! (f/watch! counter*2 prn))

@(f/send counter inc)
;; Prints: 4

@counter ;; => 2
@counter*2 ;; => 4

(dispose!) ;; disconnects counter*2, cleans up listener
```
