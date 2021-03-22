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
  (let [task-chan (a/chan 10) ;; ??? more ???
        complete-chan (a/chan)
        error-chan (a/chan)]
    ;; main event loop which handles tasks
    (a/go-loop [task (a/<! task-chan)
                recur-args nil]
      (let [res (try
                  (let [recur-args (apply task recur-args)]
                    (cond
                      (some? recur-args)
                      [:recur task recur-args]

                      :else
                      [:done]))
                  (catch #?(:clj Exception :cljs js/Error) e
                    [:error e]))]
        (case (first res)
          :recur (let [[_ task recur-args] res]
                   (recur task recur-args))
          :done (do (a/put! complete-chan :done)
                    (recur (a/<! task-chan) nil))
          :error (do (a/put! error-chan (second res))
                     (recur (a/<! task-chan) nil)))))
    (reify
      f.s/IScheduler
      (schedule [this _ f]
        #?(:clj (let [p (promise)]
                  (a/put! task-chan f)
                  (a/go
                    (a/alts! [error-chan complete-chan])
                    (deliver p nil))
                  p)
           :cljs (js/Promise.
                  (fn [res rej]
                    (a/put! task-chan f)
                    (a/go
                      (a/alts! [error-chan complete-chan])
                      (res nil)))))))))


(comment
  (def s (async-scheduler))

  (do @(f.s/schedule s nil (fn [] (prn "hi")))
      :done))
