(ns incrd.core-test
  (:require
   [clojure.test :as t]
   [incrd.core :as i]))


(t/deftest simple
  (let [n (i/mote 0)]
    (t/is (= 0 @n))
    @(i/send n inc)
    (t/is (= 1 @n))))


(t/deftest async
  (let [n (i/mote 0)
        tx (i/send n (fn [n]
                       (Thread/sleep 10)
                       (inc n)))]
    @(i/send n inc)
    (t/is (= 1 @n))
    @tx
    (t/is (= 2 @n))))


(t/deftest diamond
  (let [n (i/mote 0)
        runs (atom 0)
        n*2 (i/reaction (fn []
                          (swap! runs inc)
                          (* @n 2)))
        n*3 (i/reaction (fn []
                          (swap! runs inc)
                          (* @n 3)))
        end (i/reaction (fn []
                          (swap! runs inc)
                          (vector @n*2 @n*3)))]
    (t/is (= [0 0] @end))
    (t/is (= 3 @runs))
    @(i/send n inc)
    (t/is (= [2 3] @end))
    (t/is (= 6 @runs))
    (t/testing "env"
      (let [env (i/env)]
        (t/is (= [0 0] (i/with-env env
                         @end)))
        (t/is (= 0 (i/with-env env
                     @n*2)))
        @(i/with-env env
           (i/send n dec))
        (t/is (= [-2 -3] (i/with-env env
                           @end)))
        (t/is (= [2 3] @end))))))
