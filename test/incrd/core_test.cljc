(ns incrd.core-test
  (:require
   [clojure.test :as t]
   [incrd.core :as i]))


(t/deftest simple
  (let [n (i/mote 0)]
    (t/is (= 0 @n))
    @(i/send n inc)
    (t/is (= 1 @n)))
  (t/testing "with-env"
    (i/with-env (i/env)
      (let [n (i/mote 0)]
        (t/is (= 0 @n))
        @(i/send n inc)
        (t/is (= 1 @n))))))


(t/deftest retry
  (let [n (i/mote 0)
        tx (i/send n (fn [n]
                       (Thread/sleep 10)
                       4))]
    @(i/send n inc)
    (t/is (= 1 @n))
    @tx
    (t/is (= 4 @n))))


(t/deftest connection
  (let [n (i/mote 0)
        calls (atom 0)
        r (i/reaction (fn []
                        (swap! calls inc)
                        (* @n 2)))]
    @(i/send n inc)
    (t/is (= i/disconnected @r))
    (t/is (= 0 @calls))

    (i/connect! r) ;; 1
    (t/is (= 2 @r))
    (t/is (= 1 @calls))

    @(i/send n inc) ;; 2
    (t/is (= 4 @r))
    (t/is (= 2 @calls))

    (i/disconnect! r)
    (t/is (not (i/connected? r)))
   @(i/send n inc)
    (t/is (= i/disconnected @r))
    (t/is (= 2 @calls) "Doesn't fire r again after d/c")))


(t/deftest remove-stale
  (let [n0 (i/mote 0)
        n1 (i/mote 0)
        calls (atom 0)
        r (i/reaction (fn []
                        (swap! calls inc)
                        (if (< @n0 2)
                          (+ @n0 @n1)
                          (* 10 @n0))))]
    (i/connect! r) ;; 1
    (t/is (= 0 @r))

    @(i/send n0 inc) ;; 2
    @(i/send n1 inc) ;; 3
    (t/is (= 2 @r))
    @(i/send n1 inc) ;; 4
    (t/is (= 3 @r))
    @(i/send n0 inc) ;; 5
    (t/is (= 20 @r))

    ;; this shouldn't fire `r`
    @(i/send n1 inc)

    (t/is (= 5 @calls))))


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
    (i/connect! end)
    (t/is (= [0 0] @end))
    (t/is (= 3 @runs))
    @(i/send n inc)
    (t/is (= [2 3] @end))
    (t/is (= 6 @runs))
    (t/testing "env"
      (let [env (i/env)]
        (i/with-env env
          (i/connect! end))
        (t/is (= [0 0] (i/with-env env
                         @end)))
        (t/is (= 0 (i/with-env env
                     @n*2)))
        @(i/with-env env
           (i/send n dec))
        (t/is (= [-2 -3] (i/with-env env
                           @end)))
        (t/is (= [2 3] @end))))))
