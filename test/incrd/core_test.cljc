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


(t/deftest input
  (let [n (i/input 0)]
    (t/is (= 0 @n))
    @(i/send n inc)
    (t/is (= 1 @n)))
  (t/testing "env"
    (i/with-env (i/env)
      (let [n (i/input 0)]
        (t/is (= 0 @n))
        @(i/send n inc)
        (t/is (= 1 @n))))))


(t/deftest retry
  (let [n (i/input 0)
        tx (i/send n (fn [n]
                       (Thread/sleep 10)
                       4))]
    @(i/send n inc)
    (t/is (= 1 @n))
    @tx
    (t/is (= 4 @n))))


(t/deftest connection
  (t/testing "connection adds ref to the env"
    (let [n (i/input 0)
          c (i/signal #(* 2 @n))]
      (t/is (= i/none @c))
      (i/connect! c)
      @(i/send n inc)
      (t/is (= 2 @c))))
  (t/testing "no refs"
    (let [c (i/signal #(+ 1 2))]
      (t/is (= i/none @c))
      (i/connect! c)
      (t/is (= 3 @c))))
  (t/testing "simple"
    (let [n (i/input 0)
          calls (atom 0)
          c (i/signal (fn []
                        (swap! calls inc)
                        (* @n 2)))]
      @(i/send n inc)
      (t/is (= i/none @c))
      (t/is (= 0 @calls))

      (i/connect! c) ;; 1
      (t/is (= 2 @c))
      (t/is (= 1 @calls))

      @(i/send n inc) ;; 2
      (t/is (= 4 @c))
      (t/is (= 2 @calls))

      (i/disconnect! c)
      (t/is (not (i/connected? c)))
      @(i/send n inc)
      (t/is (= i/none @c))
      (t/is (= 2 @calls) "Doesn't fire c again after d/c")))
  (t/testing "switch"
    (let [n (i/input 0)
          a (i/signal (constantly "a"))
          b (i/signal (constantly "b"))
          c (i/signal #(if (even? @n)
                         @a
                         @b))]
      (i/connect! c)
      (t/are [conn? x] (= conn? (i/connected? x))
        true a
        false b
        true c)
      (t/is (= "a" @c))

      @(i/send n inc) ;; 1
      (t/are [conn? x] (= conn? (i/connected? x))
        false a
        true b
        true c)
      (t/is (= "b" @c))

      (i/disconnect! c)

      (t/are [conn? x] (not (i/connected? x))
        _ a
        _ b
        _ c)))
  (t/testing "propagates"
    (let [n (i/input 0)
          a (i/signal #(deref n))
          b (i/signal #(deref a))
          c (i/signal #(deref b))]
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
  (let [n0 (i/input 0)
        n1 (i/input 0)
        calls (atom 0)
        c (i/signal (fn []
                      (swap! calls inc)
                      (if (< @n0 2)
                        (+ @n0 @n1)
                        (* 10 @n0))))]
    (i/connect! c) ;; 1
    (t/is (= 0 @c))

    @(i/send n0 inc) ;; 2
    @(i/send n1 inc) ;; 3
    (t/is (= 2 @c))
    @(i/send n1 inc) ;; 4
    (t/is (= 3 @c))
    @(i/send n0 inc) ;; 5
    (t/is (= 20 @c))

    ;; this shouldn't fire `r`
    @(i/send n1 inc)

    (t/is (= 5 @calls)))

  (t/testing "disconnects"
    (let [n (i/input 0)
          ca (i/signal #(* @n 2))
          cb (i/signal #(if (< @n 3)
                          (inc @ca)
                          42))]
      (i/connect! cb)
      (t/is (i/connected? ca))

      @(i/send n inc) ;; 1
      @(i/send n inc) ;; 2
      (t/is (i/connected? ca))

      @(i/send n inc)
      (t/is (= 42 @cb))
      (t/is (i/connected? cb))
      (t/is (not (i/connected? ca))))))


(t/deftest diamond
  (let [n (i/input 0)
        runs (atom 0)
        n*2 (i/signal (fn []
                        (swap! runs inc)
                        (* @n 2)))
        n*3 (i/signal (fn []
                        (swap! runs inc)
                        (* @n 3)))
        end (i/signal (fn []
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
    (let [n (i/input 0)
          c (i/signal #(if (< @n 3)
                         @n
                         (throw (ex-info "Too big!" {}))))]
      (i/connect! c)
      @(i/send n inc) ;; 1
      @(i/send n inc) ;; 2
      (t/is (= 2 @c))

      (t/is (thrown? Exception @(i/send n inc))) ;; 3

      (t/is (= 2 @n))
      (t/is (= 2 @c)))))


(t/deftest cutoff
  (t/testing "default - won't fire if ="
    (let [n (i/input 0)
          calls (atom {:ra 0 :rb 0})
          ca (i/signal (fn []
                         (swap! calls update :ra inc)
                         (* @n 2)))
          cb (i/signal (fn []
                         (swap! calls update :rb inc)
                         (inc @ca)))]
      (i/connect! cb)
      @(i/send n identity)

      (t/is (= 1 (:ra @calls)))
      (t/is (= 1 (:rb @calls)))))
  (t/testing "simple custom"
    (let [n (i/input 0)
          calls (atom {:odd-cutoff 0
                       :even 0
                       :even-cutoff 0
                       :odd 0})
          even-cutoff (i/signal
                       {:cutoff? (fn [old new]
                                   (even? new))}
                       (fn []
                         (swap! calls update :even-cutoff inc)
                         @n))
          odd (i/signal
               (fn []
                 (swap! calls update :odd inc)
                 @even-cutoff))

          odd-cutoff (i/signal
                      {:cutoff? (fn [old new]
                                  (odd? new))}
                      (fn []
                        (swap! calls update :odd-cutoff inc)
                        @n))
          even (i/signal
                (fn []
                  (swap! calls update :even inc)
                  @odd-cutoff))]
      (i/connect! even)
      (i/connect! odd)
      (t/are [expected key] (= expected (get @calls key))
        1 :even-cutoff
        1 :odd

        1 :odd-cutoff
        1 :even)

      (t/are [expected c] (= expected @c)
        0 even-cutoff
        0 odd

        0 odd-cutoff
        0 even)

      @(i/send n inc)
      (t/are [expected key] (= expected (get @calls key))
        2 :even-cutoff
        2 :odd

        2 :odd-cutoff
        1 :even)

      (t/are [expected c] (= expected @c)
        1 even-cutoff
        1 odd

        1 odd-cutoff
        0 even))))


(t/deftest defsig
  (let [db (i/input {:name "Will"
                     :counter 0})]

    (i/defsig greeting
      (str "Hello, " (:name @db)))

    (i/defsig even-counter
      {:cutoff? (fn [_ v] (odd? v))}
      (:counter @db))

    (def evens (i/collect [] even-counter))

    (t/is (= i/none @greeting))
    (t/is (= i/none @even-counter))

    (i/connect! greeting)
    (i/connect! evens)
    (t/is (= "Hello, Will" @greeting))
    (t/is (= 0 @even-counter))
    (t/is (= [0] @evens))

    @(i/send db update :counter inc)

    (t/is (= {:name "Will" :counter 1} @db))
    (t/is (= "Hello, Will" @greeting))
    (t/is (= 1 @even-counter))
    (t/is (= [0] @evens))

    @(i/send db update :counter inc)

    (t/is (= {:name "Will" :counter 2} @db))
    (t/is (= "Hello, Will" @greeting))
    (t/is (= 2 @even-counter))
    (t/is (= [0 2] @evens))))


(t/deftest collect
  (let [n (i/input 0)
        nums (i/collect [] n)]
    (i/connect! nums)
    (t/is (= [0] @nums))

    @(i/send n inc)
    @(i/send n inc)
    @(i/send n inc)

    (t/is (= [0 1 2 3] @nums)))
  (t/testing "collect a map"
    (let [entry (i/input [:a 0])
          map-collect (i/collect {} entry)]
      (i/connect! map-collect)
      (t/is (= {:a 0} @map-collect))

      @(i/send entry (constantly [:b 1]))
      @(i/send entry (constantly [:c 2]))
      @(i/send entry (constantly [:a 3]))

      (t/is (= {:a 3 :b 1 :c 2} @map-collect))))
  (t/testing "transducer"
    (let [n (i/input 0)
          even-n+1 (i/collect
                    []
                    (comp (filter even?) (map inc))
                    n)]
      (i/connect! even-n+1)
      (t/is (= [1] @even-n+1))

      @(i/send n inc)
      @(i/send n inc)
      @(i/send n inc)
      @(i/send n inc)
      @(i/send n inc)

      (t/is (= [1 3 5] @even-n+1))))
  (t/testing "stateful transducer"
    (let [n (i/input 0)
          first-three-n (i/collect [] (take 3) n)]
      (i/connect! first-three-n)
      (t/is (= [0] @first-three-n))

      @(i/send n inc)
      @(i/send n inc)
      @(i/send n inc)
      @(i/send n inc)
      @(i/send n inc)

      (t/is (= [0 1 2] @first-three-n)))
    (let [n (i/input 0)
          skip-3-n (i/collect [] (drop 3) n)]
      (i/connect! skip-3-n)
      (t/is (= [] @skip-3-n))

      @(i/send n inc)
      @(i/send n inc)
      @(i/send n inc) ;; 3
      @(i/send n inc) ;; 4
      @(i/send n inc) ;; 5

      (t/is (= [3 4 5] @skip-3-n)))
    (let [n (i/input 0)
          partitioner (i/collect [] (partition-by #(zero? (mod % 3))) n)]
      (i/connect! partitioner)
      (t/is (= '[] @partitioner))

      @(i/send n inc) ;; 1 (false)
      @(i/send n inc) ;; 2 (false)
      @(i/send n inc) ;; 3 (true)
      @(i/send n inc) ;; 4 (false)
      @(i/send n inc) ;; 5 (false)
      @(i/send n inc) ;; 6 (true)
      @(i/send n inc) ;; 7
      ;; the last one does not get collected, as partition-by waits for the
      ;; partition fn to return a new value before collecting all of the subseqs

      (t/is (= '[[0] [1 2] [3] [4 5] [6]] @partitioner))))
  (t/testing "depends on computation"
    (let [n (i/input 0)
          even (i/signal
                {:cutoff? (fn [_ v]
                            (odd? v))}
                #(deref n))
          evens (i/collect [] even)]
      (i/connect! evens)
      (t/is (= [0] @evens))

      @(i/send n inc)
      @(i/send n inc)
      @(i/send n inc)
      @(i/send n inc)
      @(i/send n inc)

      (t/is (= [0 2 4] @evens)))))
