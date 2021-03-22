# Goals

* An interface for incremental data computation graphs
* Cross platform: Clojure and ClojureScript
* Work can be scheduled and prioritized
* Work can be split across frames (time slicing)
* Work can be split across client and server
* Work can be split across multiple nodes
  * How do we share context?
* Graphs can be isolated 
  * Why?
  * Would it share scheduler? Potential to be too greedy if not coordinated together

## Features

- [x] Simple containers `input`
- [x] Easy reactive computations `compute`
- [x] Topological sort
- [ ] Cold watches `add-watch`
- [x] Hot watches (see Incremental)
- [ ] Internal side effects `defer`
- [ ] Task queue
- [ ] Serialize to disk
  - [ ] Stable names
- [ ] Batching/transactions
- [x] Error handling
- [x] Garbage collection `dispose!`
- [ ] Monadic API
- [x] Complex containers `source`


## API scratch

```clojure
(require '[flex.core :as flex :refer [input send with-env signal watch! dispose! defer]])

;; -- changing state

(def count (input 0))

@count ;; => 0

;; in Clojure, derefing the send blocks until value has been updated
;; in ClojureScript, sends return a promise; you cannot block
@(send count inc) ;; => nil

@count ;; => 1


;; -- using environments

(with-env (env)
  @count) ;; => 0

(def my-env (env)) ;; => #<Environment>

@(with-env my-env
   (send count (constantly 4))) ;; => nil

(with-env my-env
  @count) ;; => 4

@count ;; => 1


;; -- dependent values

(def count*2
  (signal
    (* @count 2))) ;; => #<Signal>

@count*2 ;; => 2

(with-env my-env
  @count*2) ;; => 8

@(send count inc) ;; => nil

@count*2 ;; => 4


;; -- external side effects

;; cold
(add-watch count*2 :prn (fn [_ _ _ v] 
                          (prn v)))

(remove-watch count*2 :prn)

(with-env my-env
  (add-watch count*2 :prn (fn [_ _ _ v]
                            (prn v)))
  (remove-watch count*2 :prn))

;; hot
(def watcher (watch! count*2 prn)) ;; => #<Watcher>

(dispose! watcher) ;; => nil

(def watcher 
  (with-env my-env
    (watch! count*2 prn))) ;; => #<Watcher>

(dispose! watcher) ;; => nil


;; -- internal side effects

;; side effect after each successful update
(def count*4+prn
  (signal
    (let [count*4 (* @count*2 2)]
      (defer #(prn count*4))
      count*4))) ;; => #<Signal>


;; -- priority

(def watcher (watch! count prn))

;; updates are by default flex/priority-low
(send count inc)

(with-priority flex/priority-high
  (send count (constantly 0)))

(prn @count)

;; log output:
;;  2 - the last deref
;;  0 - high priority update
;;  1 - low priority update
```


## Notes

Q: When batching, could we change independent sources at the same time? Need a way
of queuing up multiple updates to the same source, as continuity is key.


---


Q: When using remove/filter/drop/etc., should we wait to propagate until a known
value is assigned? What does being "connected?" mean then?


Q: When connecting, should we schedule a computation to be run instead of doing it
synchronously? Perhaps a `touch` fn could force the computation if necessary.

A: All computations should have an initial value. `signal`s compute their
initial value but a `:cutoff?` function can prevent future updates; `collect`
can specify an initial value and skip updates by returning the previous value
in the reducer.

--- 

`window` transducer that provides a sliding window collection of values
