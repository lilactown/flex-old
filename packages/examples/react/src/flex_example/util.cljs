(ns flex-example.util
  (:require
   [flex.core :as f]
   [helix.core :refer [$ defhook]]
   [helix.dom :as d]
   [helix.hooks :as hooks]))

;; this is a fundamental hook which manages the connection between React and
;; our incremental computation graph. will probably move into its own package
;; at some point
(defhook use-signal
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


(defhook use-synchronized-state
  [value change-fn]
  (let [[v set-v] (hooks/use-state
                   value)
        prev-value (hooks/use-ref value)]
    (hooks/use-layout-effect
     [value]
     (cond
       ;; given new value that was not initiated via change-fn
       (and (not= v value) (not= @prev-value value))
       (do (set-v value)
           (reset! prev-value value))

       ;; new value that was initiated via change-fn, keep track of this
       (not= @prev-value value)
       (reset! prev-value value)))
    [v (hooks/use-callback
        [change-fn]
        (fn [v']
          (set-v v')
          (change-fn v')))]))
