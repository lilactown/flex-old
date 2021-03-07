(require '[incrd.core :as incrd :refer [mote send reaction]])

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


(def count-str
  (reaction #(str "The count x 2 is " @count*2)))

@count-str
