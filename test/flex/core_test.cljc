(ns flex.core-test
  (:require
   [clojure.test :as t]
   [flex.core :as f]
   [flex.env :as env]))


(t/use-fixtures :each
  (fn clear-env [f]
    (env/clear-env! f/*environment*)
    (t/testing "empty env before test"
      (t/is (env/empty? f/*environment*)))
    (f)))


(t/deftest input
  (let [n (f/input 0)]
    (t/is (= 0 @n))
    @(f/send n inc)
    (t/is (= 1 @n)))
  (t/testing "env"
    (f/with-env (f/env)
      (let [n (f/input 0)]
        (t/is (= 0 @n))
        @(f/send n inc)
        (t/is (= 1 @n))))))


(t/deftest source
  (let [state (f/source
               (fn state-reducer
                 ([] (state-reducer nil [:init]))
                 ([db [event]]
                  (case event
                    :inc (update db :count inc)
                    :dec (update db :count dec)
                    (:init :reset) {:count 0}))))]
    (t/is (= {:count 0} @state))
    @(f/send state :inc 'a)
    (t/is (= {:count 1} @state))
    @(f/send state :inc)
    @(f/send state :inc)
    @(f/send state :inc)
    @(f/send state :dec)
    (t/is (= {:count 3} @state))
    @(f/send state :reset)
    (t/is (= {:count 0} @state))))


(t/deftest retry
  (let [n (f/input 0)
        tx (f/send n (fn [n]
                       (Thread/sleep 10)
                       4))]
    @(f/send n inc)
    (t/is (= 1 @n))
    @tx
    (t/is (= 4 @n))))


(t/deftest connection
  (t/testing "connection adds ref to the env"
    (let [n (f/input 0)
          c (f/signal #(* 2 @n))]
      (t/is (= f/none @c))
      (f/connect! c)
      @(f/send n inc)
      (t/is (= 2 @c))))
  (t/testing "no refs"
    (let [c (f/signal #(+ 1 2))]
      (t/is (= f/none @c))
      (f/connect! c)
      (t/is (= 3 @c))))
  (t/testing "simple"
    (let [n (f/input 0)
          calls (atom 0)
          c (f/signal (fn []
                        (swap! calls inc)
                        (* @n 2)))]
      @(f/send n inc)
      (t/is (= f/none @c))
      (t/is (= 0 @calls))

      (f/connect! c) ;; 1
      (t/is (= 2 @c))
      (t/is (= 1 @calls))

      @(f/send n inc) ;; 2
      (t/is (= 4 @c))
      (t/is (= 2 @calls))

      (f/disconnect! c)
      (t/is (not (f/connected? c)))
      @(f/send n inc)
      (t/is (= f/none @c))
      (t/is (= 2 @calls) "Doesn't fire c again after d/c")))
  (t/testing "switch"
    (let [n (f/input 0)
          a (f/signal (constantly "a"))
          b (f/signal (constantly "b"))
          c (f/signal #(if (even? @n)
                         @a
                         @b))]
      (f/connect! c)
      (t/are [conn? x] (= conn? (f/connected? x))
        true a
        false b
        true c)
      (t/is (= "a" @c))

      @(f/send n inc) ;; 1
      (t/are [conn? x] (= conn? (f/connected? x))
        false a
        true b
        true c)
      (t/is (= "b" @c))

      (f/disconnect! c)

      (t/are [conn? x] (not (f/connected? x))
        _ a
        _ b
        _ c)))
  (t/testing "propagates"
    (let [n (f/input 0)
          a (f/signal #(deref n))
          b (f/signal #(deref a))
          c (f/signal #(deref b))]
      (t/are [con? r] (= con? (f/connected? r))
        false a
        false b
        false c)

      (f/connect! c)
      (t/are [con? r] (= con? (f/connected? r))
        true a
        true b
        true c)

      (f/disconnect! c)
      (t/are [con? r] (= con? (f/connected? r))
        false a
        false b
        false c))))


(t/deftest remove-stale
  (let [n0 (f/input 0)
        n1 (f/input 0)
        calls (atom 0)
        c (f/signal (fn []
                      (swap! calls inc)
                      (if (< @n0 2)
                        (+ @n0 @n1)
                        (* 10 @n0))))]
    (f/connect! c) ;; 1
    (t/is (= 0 @c))

    @(f/send n0 inc) ;; 2
    @(f/send n1 inc) ;; 3
    (t/is (= 2 @c))
    @(f/send n1 inc) ;; 4
    (t/is (= 3 @c))
    @(f/send n0 inc) ;; 5
    (t/is (= 20 @c))

    ;; this shouldn't fire `r`
    @(f/send n1 inc)

    (t/is (= 5 @calls)))

  (t/testing "disconnects"
    (let [n (f/input 0)
          ca (f/signal #(* @n 2))
          cb (f/signal #(if (< @n 3)
                          (inc @ca)
                          42))]
      (f/connect! cb)
      (t/is (f/connected? ca))

      @(f/send n inc) ;; 1
      @(f/send n inc) ;; 2
      (t/is (f/connected? ca))

      @(f/send n inc)
      (t/is (= 42 @cb))
      (t/is (f/connected? cb))
      (t/is (not (f/connected? ca))))))


(t/deftest diamond
  (let [n (f/input 0)
        runs (atom 0)
        n*2 (f/signal (fn []
                        (swap! runs inc)
                        (* @n 2)))
        n*3 (f/signal (fn []
                        (swap! runs inc)
                        (* @n 3)))
        end (f/signal (fn []
                        (swap! runs inc)
                        (vector @n*2 @n*3)))]
    (f/connect! end)
    (t/is (= [0 0] @end))
    (t/is (= 3 @runs))
    @(f/send n inc)
    (t/is (= [2 3] @end))
    (t/is (= 6 @runs))
    (t/testing "env"
      (let [env (f/env)]
        (f/with-env env
          (f/connect! end))
        (t/is (= [0 0] (f/with-env env
                         @end)))
        (t/is (= 0 (f/with-env env
                     @n*2)))
        @(f/with-env env
           (f/send n dec))
        (t/is (= [-2 -3] (f/with-env env
                           @end)))
        (t/is (= [2 3] @end))))))


(t/deftest errors
  (f/with-env (f/env)
    (let [n (f/input 0)
          c (f/signal #(if (< @n 3)
                         @n
                         (throw (ex-info "Too big!" {}))))]
      (f/connect! c)
      @(f/send n inc) ;; 1
      @(f/send n inc) ;; 2
      (t/is (= 2 @c))

      (t/is (thrown? Exception @(f/send n inc))) ;; 3

      (t/is (= 2 @n))
      (t/is (= 2 @c)))))


(t/deftest cutoff
  (t/testing "default - won't fire if ="
    (let [n (f/input 0)
          calls (atom {:ra 0 :rb 0})
          ca (f/signal (fn []
                         (swap! calls update :ra inc)
                         (* @n 2)))
          cb (f/signal (fn []
                         (swap! calls update :rb inc)
                         (inc @ca)))]
      (f/connect! cb)
      @(f/send n identity)

      (t/is (= 1 (:ra @calls)))
      (t/is (= 1 (:rb @calls)))))
  (t/testing "simple custom"
    (let [n (f/input 0)
          calls (atom {:odd-cutoff 0
                       :even 0
                       :even-cutoff 0
                       :odd 0})
          even-cutoff (f/signal
                       {:cutoff? (fn [old new]
                                   (even? new))}
                       (fn []
                         (swap! calls update :even-cutoff inc)
                         @n))
          odd (f/signal
               (fn []
                 (swap! calls update :odd inc)
                 @even-cutoff))

          odd-cutoff (f/signal
                      {:cutoff? (fn [old new]
                                  (odd? new))}
                      (fn []
                        (swap! calls update :odd-cutoff inc)
                        @n))
          even (f/signal
                (fn []
                  (swap! calls update :even inc)
                  @odd-cutoff))]
      (f/connect! even)
      (f/connect! odd)
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

      @(f/send n inc)
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
  (let [db (f/input {:name "Will"
                     :counter 0})]

    (f/defsig greeting
      (str "Hello, " (:name @db)))

    (f/defsig even-counter
      {:cutoff? (fn [_ v] (odd? v))}
      (:counter @db))

    (def evens (f/collect [] even-counter))

    (t/is (= f/none @greeting))
    (t/is (= f/none @even-counter))

    (f/connect! greeting)
    (f/connect! evens)
    (t/is (= "Hello, Will" @greeting))
    (t/is (= 0 @even-counter))
    (t/is (= [0] @evens))

    @(f/send db update :counter inc)

    (t/is (= {:name "Will" :counter 1} @db))
    (t/is (= "Hello, Will" @greeting))
    (t/is (= 1 @even-counter))
    (t/is (= [0] @evens))

    @(f/send db update :counter inc)

    (t/is (= {:name "Will" :counter 2} @db))
    (t/is (= "Hello, Will" @greeting))
    (t/is (= 2 @even-counter))
    (t/is (= [0 2] @evens))))


(t/deftest collect
  (let [n (f/input 0)
        nums (f/collect [] n)]
    (f/connect! nums)
    (t/is (= [0] @nums))

    @(f/send n inc)
    @(f/send n inc)
    @(f/send n inc)

    (t/is (= [0 1 2 3] @nums)))
  (t/testing "collect a map"
    (let [entry (f/input [:a 0])
          map-collect (f/collect {} entry)]
      (f/connect! map-collect)
      (t/is (= {:a 0} @map-collect))

      @(f/send entry (constantly [:b 1]))
      @(f/send entry (constantly [:c 2]))
      @(f/send entry (constantly [:a 3]))

      (t/is (= {:a 3 :b 1 :c 2} @map-collect))))
  (t/testing "transducer"
    (let [n (f/input 0)
          even-n+1 (f/collect
                    []
                    (comp (filter even?) (map inc))
                    n)]
      (f/connect! even-n+1)
      (t/is (= [1] @even-n+1))

      @(f/send n inc)
      @(f/send n inc)
      @(f/send n inc)
      @(f/send n inc)
      @(f/send n inc)

      (t/is (= [1 3 5] @even-n+1))))
  (t/testing "stateful transducer"
    (let [n (f/input 0)
          first-three-n (f/collect [] (take 3) n)]
      (f/connect! first-three-n)
      (t/is (= [0] @first-three-n))

      @(f/send n inc)
      @(f/send n inc)
      @(f/send n inc)
      @(f/send n inc)
      @(f/send n inc)

      (t/is (= [0 1 2] @first-three-n)))
    (let [n (f/input 0)
          skip-3-n (f/collect [] (drop 3) n)]
      (f/connect! skip-3-n)
      (t/is (= [] @skip-3-n))

      @(f/send n inc)
      @(f/send n inc)
      @(f/send n inc) ;; 3
      @(f/send n inc) ;; 4
      @(f/send n inc) ;; 5

      (t/is (= [3 4 5] @skip-3-n)))
    (let [n (f/input 0)
          partitioner (f/collect [] (partition-by #(zero? (mod % 3))) n)]
      (f/connect! partitioner)
      (t/is (= '[] @partitioner))

      @(f/send n inc) ;; 1 (false)
      @(f/send n inc) ;; 2 (false)
      @(f/send n inc) ;; 3 (true)
      @(f/send n inc) ;; 4 (false)
      @(f/send n inc) ;; 5 (false)
      @(f/send n inc) ;; 6 (true)
      @(f/send n inc) ;; 7
      ;; the last one does not get collected, as partition-by waits for the
      ;; partition fn to return a new value before collecting all of the subseqs

      (t/is (= '[[0] [1 2] [3] [4 5] [6]] @partitioner))))
  (t/testing "depends on computation"
    (let [n (f/input 0)
          even (f/signal
                {:cutoff? (fn [_ v]
                            (odd? v))}
                #(deref n))
          evens (f/collect [] even)]
      (f/connect! evens)
      (t/is (= [0] @evens))

      @(f/send n inc)
      @(f/send n inc)
      @(f/send n inc)
      @(f/send n inc)
      @(f/send n inc)

      (t/is (= [0 2 4] @evens)))))


(defn spy
  ([] (let [state (atom [])]
        [state (fn [x] (swap! state conj x))]))
  ([f] (let [state (atom [])]
         [state (fn [x] (swap! state conj x) (f x))])))


(t/deftest watch
  (t/testing "sources"
    (let [n (f/input 0)
          [calls call] (spy)
          dispose! (f/watch! n call)]
      @(f/send n inc)
      @(f/send n inc)
      (t/is (= [1 2] @calls))
      (dispose!)
      @(f/send n inc)
      @(f/send n inc)
      (t/is (= [1 2] @calls))))
  (t/testing "signals"
    (let [n (f/input 0)
          [acalls awatch] (spy)
          [bcalls bwatch] (spy)
          [ccalls cwatch] (spy)
          a (f/signal #(* 2 @n))
          b (f/signal #(if (even? @n)
                         "even"
                         "odd"))
          c (f/signal {:cutoff? (fn [_ v] (even? v))} #(deref n))
          adispose! (f/watch! a awatch)
          bdispose! (f/watch! b bwatch)
          cdispose! (f/watch! c cwatch)]
      @(f/send n inc)
      @(f/send n inc)
      (t/is (= [2 4] @acalls))
      (t/is (= ["odd" "even"] @bcalls))
      (t/is (= [1] @ccalls) "cutoff works")

      (adispose!)
      @(f/send n inc)
      @(f/send n inc)
      (t/is (= [2 4] @acalls) "dispose works (no change)") ;; no change
      (t/is (= ["odd" "even" "odd" "even"] @bcalls))
      (t/is (= [1 3] @ccalls)))))