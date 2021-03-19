(ns flex.scheduler)


(defprotocol IScheduler
  (schedule [scheduler priority f])
  (cancel [scheduler task])
  (pause [scheduler])
  (resume [scheduler]))


(deftype ExtremelyDumbPromiseScheduler []
  IScheduler
  (schedule [this _ f]
    (-> (js/Promise.resolve)
        (.then f))))


(defn promise-scheduler
  []
  (->ExtremelyDumbPromiseScheduler))
