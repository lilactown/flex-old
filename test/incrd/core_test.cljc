(ns incrd.core-test
  (:require
   [clojure.test :as t]
   [incrd.core :as i]
   [incrd.env :as env]))


(t/use-fixtures :each
  (fn clear-env [f]
    (env/clear-env! i/*environment*)
    (t/testing "empty env before test"
      (t/is (env/empty? i/*environment*)))
    (f)))


(t/deftest simple
  (let [n (i/mote 0)]
    (t/is (= 0 @n))
    @(i/send n inc)
    (t/is (= 1 @n)))
  (t/testing "env"
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
  (t/testing "connection adds ref to the env"
    (let [n (i/mote 0)
          r (i/reaction #(* 2 @n))]
      (i/connect! r)
      @(i/send n inc)
      (t/is (= 2 @r))))
  (t/testing "simple"
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
  (t/testing "propagates"
    (let [n (i/mote 0)
          a (i/reaction #(deref n))
          b (i/reaction #(deref a))
          c (i/reaction #(deref b))]
      (t/are [con? r] (= con? (i/connected? r))
        false a
        false b
        false c)

      (i/connect! c)
      (t/are [con? r] (= con? (i/connected? r))
        true a
        true b
        true c)

      (i/disconnect! c)
      (t/are [con? r] (= con? (i/connected? r))
        false a
        false b
        false c))))


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


(t/deftest errors
  (i/with-env (i/env)
    (let [n (i/mote 0)
          r (i/reaction #(if (< @n 3)
                           @n
                           (throw (ex-info "Too big!" {}))))]
      (i/connect! r)
      @(i/send n inc) ;; 1
      @(i/send n inc) ;; 2
      (t/is (= 2 @r))

      (t/is (thrown? Exception @(i/send n inc))) ;; 3

      (t/is (= 2 @n))
      (t/is (= 2 @r)))))


(t/deftest cutoff
  (t/testing "default"
    (let [n (i/mote 0)
          calls (atom {:ra 0 :rb 0})
          ra (i/reaction (fn []
                           (swap! calls update :ra inc)
                           (* @n 2)))
          rb (i/reaction (fn []
                           (swap! calls update :rb inc)
                           (inc @ra)))]
      (i/connect! rb)
      @(i/send n identity)

      (t/is (= 1 (:ra @calls)))
      (t/is (= 1 (:rb @calls)))))
  (t/testing "custom"
    (let [n (i/mote 0)
          calls (atom {:ra 0 :rb 0})
          ra (i/reaction
              (fn []
                (swap! calls update :ra inc)
                (* @n 2))
              ;; use custom transducer to cut off
              (remove #(< % 3)))
          rb (i/reaction (fn []
                           (swap! calls update :rb inc)
                           (inc @ra)))]
      (i/connect! rb)

      @(i/send n inc)
      (t/is (= 2 (:ra @calls)))
      (t/is (= 1 (:rb @calls))))))
