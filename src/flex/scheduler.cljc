(ns flex.scheduler)


(defprotocol IScheduler
  (schedule [scheduler priority f]))


(defn- run-task!
  [task]
  (loop [task task
         recur-args nil]
    (let [recur-args (apply task recur-args)]
      ;; TODO bring the heap?
      (cond
        (some? recur-args)
        (recur task recur-args)

        :else
        nil))))


(deftype ExtremelyDumbScheduler []
  IScheduler
  (schedule [this _ task]
    #?(:clj (future
              (Thread/sleep 10)
              (run-task! task))
       :cljs (-> (js/Promise.resolve)
                 ;; next tick
                 (.then #(run-task! task))))))


(defn extremely-dumb-scheduler
  []
  (->ExtremelyDumbScheduler))
