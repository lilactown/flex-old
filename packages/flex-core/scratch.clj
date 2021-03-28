(require '[flex.core :as f :refer [input send signal with-env env]])

;; -- changing state

(def count (input 0))

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
  (signal #(* 2 @count)))

(f/connect! count*2)

@count*2

(send count inc)

@count

@count*2


(def count*3
  (signal #(* 3 @count)))

(def end
  (signal #(vector @count*2 @count*3)))

(f/connect! end)

@count*3

@end


(def my-env (env))

(with-env my-env
  (f/connect! end)
  @end)

(with-env my-env
  (send count inc)
  (send count inc))

(with-env my-env @end)
