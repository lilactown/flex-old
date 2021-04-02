(ns flex.env
  (:refer-clojure :exclude [empty?]))


(def ^:private initial-env
  {:version (gensym "env")
   :scheduler nil
   :values {}
   :graph {}
   :refs {}})


(defn create-env
  "Creates a new environment"
  [{:keys [scheduler]}]
  (atom (assoc initial-env
               :version (gensym "env")
               :scheduler scheduler)))


(defn current-val
  [env* id initial]
  (-> @env* :values (get id initial)))


(defn set-val
  [env id v]
  (assoc-in env [:values id] v))


(defn set-val!
  [env* id v]
  (swap! env* set-val id v)
  v)


(defn clear-val!
  [env* id]
  (swap! env* update :values dissoc id))



(defn add-relation
  [env src-id computation-id]
  (-> env
      (update-in [:graph :sources src-id]
                 (fnil conj #{}) computation-id)
      (update-in [:graph :computations computation-id]
                 (fnil conj #{}) src-id)))


(defn add-relation!
  [env* src-id computation-id]
  (swap! env* add-relation src-id computation-id))


(defn remove-relation
  [env src-id computation-id]
  (-> env
      (update-in [:graph :sources src-id] disj computation-id)
      (update-in [:graph :computations computation-id] disj src-id)))


(defn remove-relation!
  [env* src-id computation-id]
  (swap! env* remove-relation src-id computation-id))


(defn clear-from-relations
  [env id]
  (-> env
      (update-in [:graph :sources] dissoc id)
      (update-in [:graph :computations] dissoc id)))


(defn clear-from-relations!
  [env* id]
  (swap! env* clear-from-relations id))


(defn add-watcher!
  [env* id f]
  (swap! env* update-in [:graph :watches id] (fnil conj #{}) f))


(defn remove-watcher!
  [env* id f]
  (swap! env* update-in [:graph :watches id] disj f))


(defn relations
  [env id]
  {:computations (get-in env [:graph :sources id] #{})
   :deps (get-in env [:graph :computations id] #{})
   :watches (get-in env [:graph :watches id] #{})})


(defn relations!
  [env* id]
  (relations @env* id))


(defn add-ref
  [env id o]
  (assoc-in env [:refs id :obj] o))


(defn add-ref!
  [env* id o]
  (swap! env* add-ref id o))


(defn get-ref
  [env* id]
  (get-in @env* [:refs id :obj]))


(defn clear-ref
  [env id]
  (update env :refs dissoc id))


(defn clear-ref!
  [env* id]
  (swap! env* clear-ref id))


(defn set-order
  [env id order]
  (assoc-in env [:refs id :order] order))


(defn set-order!
  [env* id order]
  (swap! env* set-order id order))


(defn get-order
  [env* id]
  (get-in @env* [:refs id :order] 0))


(defn branch
  [env*]
  (atom (assoc @env* :parent @env*)))


(defn is-parent?
  [env branch]
  (= @env (:parent @branch)))


(defn commit!
  [env branch]
  (reset! env (dissoc @branch :parent)))


(defn clear-env!
  [env]
  (swap! env
         (fn [env]
           (merge
            initial-env
            (select-keys env [:version :scheduler])))))


(defn empty?
  [env]
  (= (dissoc initial-env :version :scheduler)
     (dissoc @env :version :scheduler)))
