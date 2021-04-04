(ns flex-example.core
  (:require
   [flex.devtools :as f.d]
   [flex-example.counter :as c]
   [flex-example.temperature :as temp]
   [flex-example.flights :as flight]
   [flex-example.timer :as t]
   [flex-example.crud :as crud]
   [helix.core :refer [defnc $]]
   [helix.dom :as d]
   ["react-dom" :as rdom]))


(defnc app
  []
  (d/div
   {:style {:width "500px"}}
   (d/h1 "7GUIs in ClojureScript/Helix/Flex")
   ($ c/counter)
   (d/hr)
   ($ temp/converter)
   (d/hr)
   ($ flight/booker)
   (d/hr)
   ($ t/timer)
   (d/hr)
   ($ crud/app)))


(defn ^:dev/after-load start!
  []
  (rdom/render ($ app) (js/document.getElementById "app")))


(defn init!
  []
  (start!))
