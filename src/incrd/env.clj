(ns incrd.env)


(defn create-env
  "Creates a new environment"
  []
  (atom {:version (gensym "env")
         :values {}
         :graph {}
         :refs {}}))


(defn current-val
  [env id initial]
  (-> @env :values (get id initial)))


(defn set-val!
  [env id v]
  (swap! env assoc-in [:values id] v))


(defn add-relation!
  [env src-id reaction-id]
  (swap! env
         (fn [env-map]
           (-> env-map
               (update-in [:graph :sources src-id]
                          (fnil conj #{}) reaction-id)
               (update-in [:graph :reactions reaction-id]
                          (fnil conj #{}) src-id)))))


(defn remove-relation!
  [env src-id reaction-id]
  (swap! env
         (fn [env-map]
           (-> env-map
               (update-in [:graph :sources src-id] disj reaction-id)
               (update-in [:graph :reactions reaction-id] disj src-id)))))


(defn relations
  [env id]
  (let [env-map @env]
    {:reactions (get-in env-map [:graph :sources id] #{})
     :deps (get-in env-map [:graph :reactions id] #{})}))


(defn add-ref!
  [env id o]
  (swap! env assoc-in [:refs id :obj] o))


(defn get-ref
  [env id]
  (get-in @env [:refs id :obj]))


(defn set-order!
  [env id order]
  (swap! env assoc-in [:refs id :order] order))


(defn get-order
  [env id]
  (get-in @env [:refs id :order] 0))


(defn branch
  [env]
  (atom (assoc @env :parent @env)))


(defn is-parent?
  [env branch]
  (= @env (:parent @branch)))


(defn commit!
  [env branch]
  (reset! env (dissoc @branch :parent)))
