(require '[incrd.core :as incrd :refer [mote send]])

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
