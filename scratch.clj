(require '[incrd.core :as incrd :refer [mote send reaction with-env env]])

;; -- changing state

(def count (mote 0))

@count

;; in Clojure, derefing the send blocks until value has been updated
;; in ClojureScript, send returns a promise; you cannot block
(do
  @(send count inc)
  @count)


(do (send count inc)
    @count)


(do (send count (fn [n]
                  (Thread/sleep 10)
                  (dec n)))
    @(send count dec)
    @count)


(def count*2
  (reaction #(* 2 @count)))

@count*2

(send count inc)

@count

@count*2


(def count*3
  (reaction #(* 3 @count)))

(def end
  (reaction #(vector @count*2 @count*3)))

@count*3

@end


(def my-env (env))

(with-env my-env
  @end)

(with-env my-env
  (send count inc)
  (send count inc))
