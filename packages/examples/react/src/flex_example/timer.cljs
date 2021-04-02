(ns flex-example.timer
  (:require
   [flex.core :as f]
   [flex-example.util :refer [use-signal]]
   [helix.core :refer [defnc $]]
   [helix.dom :as d]
   [helix.hooks :as hooks]))


;; a global signal which will continually update every 100 ms.
;; this way our ticker will continue to update even when we construct a new
;; countdown signal
(def ticker
  (let [tick (f/input 0)
        interval (js/setInterval
                  #(f/send tick + 100)
                  100)]
    (f/signal
     {:on-disconnect #(do (prn :ticker/dc)
                          (js/clearInterval interval))}
     @tick)))


;; `signal-fn` creates signal "factory" which must return a signal.
;; calls with the same arguments will all see the same signal, until the
;; signal is disconnected from the graph.
(def countdown
  (f/signal-fn
   [start end _]
   (let [;; dereferences inside of this function will not cause connection,
         ;; so we check to see if it equals `f/none`; this means we haven't
         ;; connected yet (e.g. during app startup) and default to 0.
         start-tick @ticker
         start-tick (if (= f/none start-tick)
                      0
                      ;; else, calculate the relative time since our timer
                      ;; started
                      (- start-tick start))]
     (f/signal
      ;; `cutoff?` will cause listeners to stop receiving if it returns true
      {:cutoff? (fn [_ t]
                  (< end t))
       :on-disconnect #(prn :countdown/dc)}
      (- @ticker start-tick)))))


(defnc timer
  []
  ;; pass in a unique `id` for each timer run to bust the `signal-fn` cache.
  ;; this makes it so that setting the config to [0 5000] works if we haven't
  ;; modified the duration (since it's the same as the initial state)
  (let [[[start duration id] set-config] (hooks/use-state [0 5000 (gensym)])
        time (use-signal (countdown start duration id))]
    (d/div
     (d/div "Elapsed time: " (d/meter {:min 0 :max duration :value time}))
     (d/div (/ time 1000) "s")
     (d/div
      "Duration: "
      (d/input
       {:type "range"
        :value duration
        :on-change #(set-config [time (js/parseInt (.. % -target -value)) id])
        :min 0
        :max 30000}))
     (d/button {:on-click #(set-config [0 duration (gensym)])} "Reset"))))
