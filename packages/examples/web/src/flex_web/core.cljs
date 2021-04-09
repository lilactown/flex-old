(ns flex-web.core
  (:require
   [flex.core :as f]
   ["lit-html" :as lit]
   [shadow.cljs.modern :refer [js-template]]))


(def db (f/input {:names ["Will" "Sarah" "Georgia"]}))


(def greeting-name
  (f/signal-fn
   []
   (let [selected (f/input 0)
         interval (js/setInterval
                   #(f/send selected inc)
                   1000)]
     (f/signal
      {:on-disconnect #(do (prn :dc)
                           (js/clearInterval interval))}
      (let [names (:names @db)]
        (get names (mod @selected (count names))))))))


(f/defsig app
  (js-template lit/html "Hello, " @(greeting-name) "!"))



;;
;; App setup
;;

(defonce app-dispose (atom nil))

(defn ^:dev/after-load start!
  []
  (prn :start)
  (when-let [dispose! @app-dispose]
    (prn :dispose)
    (dispose!))
  (let [container (js/document.getElementById "app")
        dispose! (f/watch! app #(lit/render % container))]
    (lit/render @app container)
    (reset! app-dispose dispose!)))


(defn init!
  []
  (start!))
