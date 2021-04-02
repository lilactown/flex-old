(ns flex-example.crud
  (:require
   [clojure.string :as s]
   [flex.core :as f]
   [flex-example.util :refer [use-signal]]
   [helix.core :refer [defnc $]]
   [helix.dom :as d]
   [helix.hooks :as hooks]))


(defn- crud-reducer
  ([] ["Bar, Foo" "Acton, Will"])
  ([state event]
   (case (first event)
     :create (let [[_ fname surname] event]
               (conj state (str surname ", " fname)))
     :update (let [[_ idx fname surname] event]
               (assoc state idx (str surname ", " fname)))
     :delete (let [[_ idx] event]
               (into
                []
                (comp
                 (map-indexed vector)
                 (filter (fn [[i _]] (not= i idx)))
                 (map second))
                state)))))

(def db (f/source crud-reducer))

(def prefix (f/source (fn
                        ([] nil)
                        ([_ [v]] v))))

(f/defsig filtered-db
  (let [db @db
        prefix @prefix]
    (if (some? prefix)
      (filterv #(s/starts-with? (s/lower-case %) (s/lower-case prefix)) db)
      db)))


(defnc app
  []
  (let [entries (use-signal filtered-db)
        [selected select] (hooks/use-state nil)
        [name set-name] (hooks/use-state "")
        [surname set-surname] (hooks/use-state "")]
    (d/div
     (d/div
      "Filter prefix: "
      (d/input {:on-change #(f/send prefix (.. % -target -value))}))
     (d/select
      {:value (str selected)
       :size 5
       :style {:width 240}}
      (for [[i entry] (map-indexed vector entries)]
        (d/option
         {:key (str [i entry])
          :on-click #(select i)
          :value (str i)}
         entry)))
     (d/div
      (d/div (d/label
              "Name: "
              (d/input
               {:value name
                :on-change #(set-name (.. % -target -value))})))
      (d/div (d/label
              "Surname: "
              (d/input
               {:value surname
                :on-change #(set-surname (.. % -target -value))}))))
     (d/div
      (d/button {:on-click #(f/send db :create name surname)} "Create")
      (d/button
       {:disabled (nil? selected)
        :on-click #(f/send db :update selected name surname)}
       "Update")
      (d/button
       {:disabled (nil? selected)
        :on-click #(f/send db :delete selected)}
       "Delete")))))
