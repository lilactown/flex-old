(require '[incrd.core :as incrd :refer [mote send]])

;; -- changing state

(def count (mote 0))

@count

;; in Clojure, derefing the send blocks until value has been updated
;; in ClojureScript, sends return a promise; you cannot block
@(send count inc)

@count

(do (send count inc)
    @count)
