(ns flex.core-benchmark
  (:require
   [flex.core :as f]
   [criterium.core :as c]))


#_(require '[clj-async-profiler.core :as prof])


(defn fib*
  ([limit] (fib* limit [0 1]))
  ([limit [prev cur]]
   (let [next (+ prev cur)]
     (if (< next limit)
       (recur limit [cur next])
       [prev cur]))))

(defn run-graph! [times]
  (let [env (f/env)
        db (f/input {:limit 0 :chars "a"})
        limit (f/signal (:limit @db))
        chars (f/signal (:chars @db))
        fib (f/signal ([] (fib* @limit))
                      ([v] (fib* @limit v)))
        combo (f/signal [(second @fib) @chars])
        ;; combinations (f/collect [] combo)
        ]
    (f/with-env env
      (f/connect! combo))
    (doseq [_ (range times)]
      (f/with-env env
        @(f/send db update :limit inc)
        @(f/send db update :chars str "b")))
    (f/with-env env @combo)))

#_(do (time (run-graph! 100000)) nil)

#_(c/quick-bench (run-graph! 1000))

#_(prof/profile (run-graph! 1000))

#_(prof/serve-files 8080)

(defn run-calc! [times]
  (loop [n times
         limit? true
         db {:limit 0 :chars "a"}
         ;; combinations []
         fib (fib* 0)]
    (if (zero? n)
      #_combinations fib
      (let [limit (:limit db)
            chars (:chars db)
            ;; recalculate all fibs every time
            fib (fib* limit)]
        (if limit?
          (recur
           n
           (not limit?)
           (-> db
               (update :limit inc))
           #_(conj combinations (vector (second fib) chars))
           fib)
          (recur
           (dec n)
           (not limit?)
           (-> db
               (update :chars str "b"))
           #_(conj combinations (vector (second fib) chars))
           fib))))))


#_(c/quick-bench (run-times! 1000))

#_(do (time (run-calc! 100000)) nil)
