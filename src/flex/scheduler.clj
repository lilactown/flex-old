(ns flex.scheduler)


(defprotocol IScheduler
  (schedule [scheduler priority f]))


(deftype ExtremelyDumbFutureScheduler []
  IScheduler
  (schedule [this _ task]
    (future
      (Thread/sleep 10)
      (loop [task task
             recur-args nil
             micro-tasks ()]
        (let [[next-tasks recur-args] (task recur-args)]
          ;; TODO bring the heap?
          (cond
            (some? recur-args)
            (recur task recur-args (into micro-tasks next-tasks))

            (seq micro-tasks)
            (recur (first micro-tasks) nil (rest micro-tasks))

            :else
            nil)))))
  #_(cancel [_ _])
  #_(pause [_])
  #_(resume [_]))


(defn future-scheduler
  []
  (->ExtremelyDumbFutureScheduler))
