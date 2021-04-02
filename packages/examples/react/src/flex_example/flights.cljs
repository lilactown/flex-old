(ns flex-example.flights
  (:require
   [flex.core :as f]
   [flex-example.util :refer [use-signal]]
   [helix.core :refer [defnc $]]
   [helix.dom :as d]
   [helix.hooks :as hooks]))


(defnc booker
  []
  (d/div "booker"))
