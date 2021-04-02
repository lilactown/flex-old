(ns flex.reagent-benchmark
  (:require
   [flex.async-scheduler :as f.a]
   [flex.core :as f]
   [flex.scheduler :as f.s]
   [reagent.core :as r]))


(defn timep [f]
  (let [t (.getTime (js/Date.))]
    (-> (f)
        (.then #(println (- (.getTime (js/Date.)) t) "msecs")))))


(defn benchp [f]
  )


(defn reagent-graph
  []
  (let [db (r/atom {:person/id {0 {:name ""}}})
        people (r/track #(:person/id @db))
        person0 (r/track #(get @people 0))
        person0-name (r/track #(:name @person0))]
    (js/Promise.
     (fn [res _]
       (doseq [c "William"]
         (swap! db update-in [:person/id 0 :name] str c))
       (r/track! #(when (= @person0-name "William")
                    (res @person0-name)))))))


#_(timep reagent-graph)


(defn flex-graph
  []
  (let [env (f/env ;; :scheduler (f.s/->SynchronousSchedulerDoNotUse)
                   )
        db (f/input {:person/id {0 {:name ""}}})
        people (f/signal (:person/id @db))
        person0 (f/signal (get @people 0))
        person0-name (f/signal (:name @person0))]
    (js/Promise.
     (fn [res _]
       (f/with-env env
         (f/watch!
          person0-name
          (fn [v]
            (when (= v "William")
              (res v)))))
       (doseq [c "William"]
         (f/with-env env
           (f/send db update-in [:person/id 0 :name] str c)))))))


#_(timep flex-graph)
