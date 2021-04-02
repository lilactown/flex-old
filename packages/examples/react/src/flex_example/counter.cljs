(ns flex-example.counter
  (:require
   [flex.core :as f]
   [flex-example.util :refer [use-signal]]
   [helix.core :refer [defnc $]]
   [helix.dom :as d]
   [helix.hooks :as hooks]))


(def count-input (f/input 0))


(defnc counter
  []
  (let [count (use-signal count-input)]
    (d/div
     (d/div
      count " " (d/button {:on-click #(f/send count-input inc)} "Count")))))
