(ns flex-example.temperature
  (:require
   [flex.core :as f]
   [flex-example.util :refer [use-signal]]
   [helix.core :refer [defnc $]]
   [helix.dom :as d]
   [helix.hooks :as hooks]))


(defn valid-number?
  [x]
  (and (not (js/Number.isNaN (js/parseFloat x)))
       (js/isFinite x)))


(defn C->F
  [C]
  (js/Math.round (+ (* C (/ 9 5)) 32)))


(defn F->C
  [F]
  (js/Math.round (* (- F 32) (/ 5 9))))


(def temperature
  (f/source
   (fn
     ([] {})
     ([state event]
      (case (first event)
        :F-change (let [[_ F] event]
                    (if (valid-number? F)
                      (assoc state
                             :F F
                             :C (str (F->C (js/parseFloat F))))
                      (assoc state :F F)))
        :C-change (let [[_ C] event]
                    (if (valid-number? C)
                      (assoc state
                             :C C
                             :F (str (C->F (js/parseFloat C))))
                      (assoc state :C C))))))))


(defnc converter
  []
  (let [{:keys [F C]
         :or {F "" C ""}} (use-signal temperature)]
    (prn F C)
    (d/div
     (d/input
      {:style (cond
                (not (or (= "" C) (valid-number? C)))
                {:background "red"}

                (and (= "" F) (not= "" C))
                {:background "grey"})
       :value C
       :on-change #(f/send temperature :C-change (.. % -target -value))})
     " Celcius = "
     (d/input
      {:style (cond
                (not (or (= "" F) (valid-number? F)))
                {:background "red"}

                (and (= "" C) (not= "" F))
                {:background "grey"})
       :value F
       :on-change #(f/send temperature :F-change (.. % -target -value))})
     " Fahrenheit")))
