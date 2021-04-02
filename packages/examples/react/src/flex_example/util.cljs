(ns flex-example.util
  (:require
   [flex.core :as f]
   [helix.hooks :as hooks]))

;; this is a fundamental hook which manages the connection between React and
;; our incremental computation graph. will probably move into its own package
;; at some point
(defn use-signal
  [s]
  (hooks/use-subscription
   (hooks/use-memo
    :auto-deps
    {:get-current-value (fn []
                          ;; ensure `s` is connected before derefing
                          (when-not (f/connected? s)
                            (f/connect! s))
                          (deref s))
     :subscribe (fn [cb]
                  ;; watch! returns a dispose function that use-subscription
                  ;; will use on unmount
                  (f/watch! s cb))})))


