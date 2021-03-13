# incrd

Goals
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

- [x] Simple containers `mote`
- [x] Easy reactive computations `reaction`
- [x] Topological sort
- [ ] Cold watches `add-watch`
- [ ] Hot watches (see Incremental)
- [ ] Internal side effects `defer`
- [ ] Task queue
- [ ] Serialize to disk
  - [ ] Stable names
- [ ] Batching/transactions
- [ ] Error handling
- [ ] Garbage collection `dispose!`
- [ ] Monadic API
- [ ] Complex containers `source`


## API scratch

```clojure
(require '[incrd.core :as incrd :refer [mote send with-env reaction watch! dispose! defer]])

;; -- changing state

(def count (mote 0))

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
  (reaction
    (* @count 2))) ;; => #<Reaction>

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
  (reaction
    (let [count*4 (* @count*2 2)]
      (defer #(prn count*4))
      count*4))) ;; => #<Reaction>


;; -- priority

(def watcher (watch! count prn))

;; updates are by default incrd/priority-low
(send count inc)

(with-priority incrd/priority-high
  (send count (constantly 0)))

(prn @count)

;; log output:
;;  2 - the last deref
;;  0 - high priority update
;;  1 - low priority update
```


## Notes

When batching, could we change independent sources at the same time? Need a way
of queuing up multiple updates to the same source, as continuity is key.
