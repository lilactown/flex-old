(ns flex-example.core
  (:require
   [flex.core :as f]
   [helix.core :refer [defnc $]]
   [helix.dom :as d]
   [helix.hooks :as hooks]
   ["react-dom" :as rdom]))


(defonce app-db
  (f/input {:counter 0
            :name "Theodore"}))


(def subscribe
  (f/signal-fn
    [path]
    (f/signal (get-in @app-db path))))


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


(defn use-sub
  [path]
  (use-signal (subscribe path)))


(defnc app
  []
  (let [db (use-signal app-db)
        counter (use-sub [:counter])]
    (prn :render)
    (d/div
     (d/div (pr-str db))
     (d/div
      (d/button {:on-click #(f/send app-db update :counter inc)} counter)))))


(defn ^:dev/after-load start!
  []
  (rdom/render ($ app) (js/document.getElementById "app")))


(defn init!
  []
  (start!))
