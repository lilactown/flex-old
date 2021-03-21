(ns flex.async-scheduler
  (:require
   [clojure.core.async :as a]
   [flex.scheduler :as f.s]))


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


(defn async-scheduler
  []
  (let [task-chan (a/chan 10)
        complete-chan (a/chan)]
    (a/go-loop [task (a/<! task-chan)
                recur-args nil]
      (let [[recur-args] (apply task recur-args)]
        (cond
          (some? recur-args)
          (recur task recur-args)

          :else
          (do
            (a/put! complete-chan :done)
            (recur (a/<! task-chan) nil)))))
    (reify
      f.s/IScheduler
      (schedule [this _ f]
        (let [p (promise)]
          (a/put! task-chan f)
          (a/take! complete-chan (fn [_ ] (deliver p nil)))
          p)))))


(comment
  (def s (async-scheduler))

  (do @(f.s/schedule s nil (fn [_] (prn "hi")))
      :done))
