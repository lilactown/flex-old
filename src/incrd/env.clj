(ns incrd.env)


(defn create-env
  "Creates a new environment"
  []
  (atom {:version (gensym "env")
         :values {}
         :graph {}}))


(defn current-val
  [env id initial]
  (-> @env :values (get id initial)))


(defn set-val!
  [env id v]
  (swap! env assoc-in [:values id] v))


(defn branch
  [env]
  (atom (assoc @env :parent @env)))


(defn is-parent?
  [env branch]
  (= @env (:parent @branch)))


(defn commit!
  [env branch]
  (reset! env (dissoc @branch :parent)))
