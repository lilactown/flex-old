(ns flex-example.core
  (:require
   [flex.core :as f]
   [helix.core :refer [defnc $]]
   [helix.dom :as d]
   [helix.hooks :as hooks]
   ["react-dom" :as rdom]))


;; create a source with our app state
(defonce app-db
  (f/input {:counter 0
            :name "Theodore"}))


;; create a memoized factory for creating new signals based on `app-db`
;; this way we can call this function inside of render and get the same signal
;; as the first call, avoiding unnecessary GC
(def subscribe
  (f/signal-fn
    [path]
    (f/signal (get-in @app-db path))))


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


;; give a path into app-db, and it will subscribe to it in a GC friendly way :)
(defn use-sub
  [path]
  (use-signal (subscribe path)))


(defnc app
  []
  (let [db @app-db ;; this will not automatically re-render

        ;; re-render if `:name` or `:counter` changes
        name (use-sub [:name])
        counter (use-sub [:counter])

        ;; local state to make "synchronous" changes
        [local-name set-local-name] (hooks/use-state
                                     ;; initial state
                                     (:name db))]
    (prn :render)
    (d/div
     (d/div (pr-str db))
     (d/div
      "Name: "
      (d/input
       {:value local-name
        :on-change #(set-local-name (.. % -target -value))})
      " "
      (d/button
       {:on-click #(f/send app-db assoc :name local-name)}
       "Submit"))
     (d/div
      "Counter: "
      (d/button {:on-click #(f/send app-db update :counter inc)} counter)))))


(defn ^:dev/after-load start!
  []
  (rdom/render ($ app) (js/document.getElementById "app")))


(defn init!
  []
  (start!))
