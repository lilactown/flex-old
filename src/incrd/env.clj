(ns incrd.env
  (:refer-clojure :exclude [empty?]))


(def ^:private initial-env
  {:version (gensym "env")
   :values {}
   :graph {}
   :refs {}})


(defn create-env
  "Creates a new environment"
  []
  (atom initial-env))


(defn current-val
  [env id initial]
  (-> @env :values (get id initial)))


(defn set-val!
  [env id v]
  (swap! env assoc-in [:values id] v))


(defn clear-val!
  [env id]
  (swap! env update :values dissoc id))


(defn add-relation!
  [env src-id computation-id]
  (swap! env
         (fn [env-map]
           (-> env-map
               (update-in [:graph :sources src-id]
                          (fnil conj #{}) computation-id)
               (update-in [:graph :computations computation-id]
                          (fnil conj #{}) src-id)))))


(defn remove-relation!
  [env src-id computation-id]
  (swap! env
         (fn [env-map]
           (-> env-map
               (update-in [:graph :sources src-id] disj computation-id)
               (update-in [:graph :computations computation-id] disj src-id)))))


(defn relations
  [env id]
  (let [env-map @env]
    {:computations (get-in env-map [:graph :sources id] #{})
     :deps (get-in env-map [:graph :computations id] #{})}))


(defn add-ref!
  [env id o]
  (swap! env assoc-in [:refs id :obj] o))


(defn get-ref
  [env id]
  (get-in @env [:refs id :obj]))


(defn clear-ref!
  [env id]
  (swap! env update :refs dissoc id))


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


(defn clear-env!
  [env]
  (reset! env initial-env))


(defn empty?
  [env]
  (= initial-env @env))
