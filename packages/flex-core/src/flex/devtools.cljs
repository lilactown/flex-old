(ns flex.devtools
  (:require
   [cljs.pprint :refer [pprint]]
   [flex.core :as f]))


(defn inspect-env
  ([] (inspect-env f/default-env))
  ([env]
   (let [win (js/window.open "" "" "width=800, height=500, left=300, top=200")
         container (.. win -document (createElement "pre"))]
     (.. win -document -body (appendChild container))
     (add-watch
      env :inspect
      (fn [_ _ _ v]
        (set!
         (.-innerText container)
         (with-out-str (pprint v)))))
     (set! (.-onunload win) (fn []
                              (remove-watch env :inspect))))))
