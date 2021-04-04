(ns flex.devtools
  (:require
   [cljs.pprint :refer [pprint]]
   [flex.core :as f]
   [flex.env :as f.e]
   ["cytoscape" :as cytoscape]
   ["cytoscape-dagre" :as dagre]))


(cytoscape/use dagre)


(defn- env->nodes
  [env]
  (-> (for [[id v] (get env :values)]
        (let [ref (get-in env [:refs id :obj])]
          #js {:data #js {:id (str id)
                          :label (str id)}
               :classes (cond-> []
                          (satisfies? f/ISource ref)
                          (conj "src")

                          (satisfies? f/IComputation ref)
                          (conj "incr")

                          true (to-array))
               :scratch #js {:_value v}}))
      (to-array)))


(defn- env->edges
  [env]
  (->> (for [[id targets] (get-in env [:graph :sources])]
         (for [target targets]
           #js {:data #js {:id (str id "->" target)
                           :source (str id)
                           :target (str target)}}))
       (mapcat identity)
       (to-array)))


(def cy-styles
  #js [#js {:selector "node"
            :style #js {:label "data(label)"
                        :text-halign "center"
                        :text-valign "bottom"}}
       #js {:selector "node.src"
            :style #js {:shape "round-rectangle"}}])


(def cy-container-css-string
  "
html, body, #container {
  margin: 0;
  padding: 0;
  height: 500px;
  width: 500px;
}
#container {
  position: relative;
  width: 100%;
}
")


(defn inspect-env
  ([] (inspect-env f/default-env))
  ([env*]
   (let [env @env*
         win (js/window.open "" "" "width=800, height=500, left=300, top=200")
         styles (doto (.. win -document (createElement "style"))
                  (-> (.-innerText)
                      (set! cy-container-css-string))
                  (as-> el
                      (.. win -document -body (appendChild el))))
         container (doto (.. win -document (createElement "div"))
                     (.setAttribute "id" "container")
                     (as-> el
                         (.. win -document -body (appendChild el))))
         cy (cytoscape
             #js {:container container
                  :elements
                  #js {:nodes (env->nodes env)
                       :edges (env->edges env)}
                  :style cy-styles
                  :layout #js {:name "dagre"
                               :nodeDimensionsIncludeLabels true}})]
     (.on cy "tap"
          (fn [e] (js/console.log e))))))
