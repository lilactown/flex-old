(ns incrd.scheduler)


(defprotocol IScheduler
  (schedule [scheduler priority f])
  (cancel [scheduler task])
  (pause [scheduler])
  (resume [scheduler]))


(deftype ExtremelyDumbFutureScheduler []
  IScheduler
  (schedule [this _ f]
    (future
      (Thread/sleep 10)
      (f)
      nil))
  (cancel [_ _])
  (pause [_])
  (resume [_]))


(defn future-scheduler
  []
  (->ExtremelyDumbFutureScheduler))
