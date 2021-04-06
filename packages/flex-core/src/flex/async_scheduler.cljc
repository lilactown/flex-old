(ns flex.async-scheduler
  (:require
   [clojure.core.async :as a]
   [flex.scheduler :as f.s]
   [flex.trace :as trace]))


(comment
  flex scheduling psuedo code

  receives a task
  -> puts on task-chan

  scheduler = go-loop
  -> take f from task-chan
  -> run (f), returns [next-fs & recur-args]
  -> if (some? recur-args)
  |  -> next-fs as queue, recur (apply f recur-args) until recur-args nil
  |  -> else, recur with (first next-fs)
  )


#?(:clj
   (defn- promise?!
     "A promise that throws if an error is delivered."
     []
     (let [d (java.util.concurrent.CountDownLatch. 1)
           v (atom d)]
       (reify
         clojure.lang.IDeref
         (deref [_]
           (.await d)
           (let [v* @v]
             (if (instance? Throwable v*)
               (throw v*)
               v*)))
         clojure.lang.IBlockingDeref
         (deref
             [_ timeout-ms timeout-val]
           (if (.await d timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
             (let [v* @v]
               (if (instance? Throwable v*)
                 (throw v*)
                 v*))
             timeout-val))
         clojure.lang.IPending
         (isRealized [this]
           (zero? (.getCount d)))
         clojure.lang.IFn
         (invoke
             [this x]
           (when (and (pos? (.getCount d))
                      (compare-and-set! v d x))
             (.countDown d)
             this))))))


(defn- promise*
  [f]
  #?(:clj (let [p (promise?!)]
            (f (fn resolve [x] (p x))
               (fn reject [e] (p e)))
            p)
     :cljs (js/Promise. f)))


(def max-iterations 1000)


(defn async-scheduler
  []
  (let [task-chan (a/chan 10) ;; ??? more ???
        complete-chan (a/chan)
        error-chan (a/chan)]
    ;; main event loop which handles tasks
    (a/go-loop [task (a/<! task-chan)
                recur-args nil
                governor max-iterations]
      (let [res (try
                  (let [recur-args (apply task recur-args)]
                    (cond
                      (some? recur-args)
                      [:recur task recur-args]

                      :else
                      [:done]))
                  (catch #?(:clj Exception :cljs js/Error) e
                    [:error e]))]
        (if (zero? governor)
          (do (a/put! error-chan (ex-info "Max iterations reached!" {}))
              (recur (a/<! task-chan) nil max-iterations))
          (case (first res)
            :recur (let [[_ task recur-args] res]
                     (recur task recur-args (dec governor)))
            :done (do (a/put! complete-chan :done)
                      #?(:cljs (trace/measure
                                :scheduler
                                :scheduler/begin))
                      (recur (a/<! task-chan) nil max-iterations))
            :error (do (a/put! error-chan (second res))
                       #?(:cljs (trace/measure
                                 :scheduler
                                 :scheduler/begin))
                       (recur (a/<! task-chan) nil max-iterations))))))
    (reify
      f.s/IScheduler
      (schedule [this _ f]
        (promise*
         (fn [resolve reject]
           #?(:cljs (trace/observe :scheduler/begin))
           (a/put! task-chan f)
           (a/go
             (let [[res c] (a/alts! [error-chan complete-chan])]
               (if (= c error-chan)
                 (reject res)
                 (resolve res))))))))))


(comment
  (def s (async-scheduler))

  (do @(f.s/schedule s nil (fn [] (prn "hi")))
      :done))
