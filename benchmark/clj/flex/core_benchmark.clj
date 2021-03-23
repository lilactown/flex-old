(ns flex.core-benchmark
  (:require
   [flex.core :as f]
   [criterium.core :as c]))


(defn fib*
  [limit]
  (loop [prev 0
         cur 1]
    (if (< cur limit)
      (recur cur (+ prev cur))
      prev)))



(c/quick-bench
 (let [env (f/env)
       db (f/input {:limit 0 :chars "a"})
       limit (f/signal #(:limit @db))
       chars (f/signal #(:chars @db))
       fib (f/signal #(fib* @limit))
       combo (f/signal #(vector @fib @chars))
       combinations (doto (f/collect [] combo)
                      (as-> c (f/with-env env
                                (f/connect! c))))]
   (doseq [_ (range 10)]
     (f/with-env env
       @(f/send db update :limit inc)
       @(f/send db update :chars str "b")))))
;; => [[0 "a"] [0 "ab"] [1 "ab"] [1 "abb"] [2 "abb"] [2 "abbb"] [3 "abbb"] [3 "abbbb"] [3 "abbbbb"] [5 "abbbbb"] [5 "abbbbbb"] [5 "abbbbbbb"] [5 "abbbbbbbb"] [8 "abbbbbbbb"] [8 "abbbbbbbbb"] [8 "abbbbbbbbbb"]]

(c/quick-bench
 (loop [n 10
        limit? true
        db {:limit 0 :chars "a"}
        combinations []]
   (if (zero? n)
     combinations
     (let [limit (:limit db)
           chars (:chars db)
           fib (fib* limit)]
       (if limit?
         (recur
          n
          (not limit?)
          (-> db
              (update :limit inc))
          (conj combinations (vector fib chars)))
         (recur
          (dec n)
          (not limit?)
          (-> db
              (update :chars str "b"))
          (conj combinations (vector fib chars))))))))
;; => [[0 "a"] [0 "a"] [0 "ab"] [1 "ab"] [1 "abb"] [2 "abb"] [2 "abbb"] [3 "abbb"] [3 "abbbb"] [3 "abbbb"] [3 "abbbbb"] [5 "abbbbb"] [5 "abbbbbb"] [5 "abbbbbb"] [5 "abbbbbbb"] [5 "abbbbbbb"] [5 "abbbbbbbb"] [8 "abbbbbbbb"] [8 "abbbbbbbbb"] [8 "abbbbbbbbb"]]
