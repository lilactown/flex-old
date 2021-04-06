(ns flex.trace
  #?(:cljs (:require-macros [flex.trace])))


#?(:cljs
   (defn observe
     [id]
     (js/performance.mark (str id))))


#?(:cljs
   (defn measure
     ([id begin-id]
      (js/performance.measure (str id) (str begin-id)))))


(defmacro watch
  [id expr]
  (if (some? (:ns &env))
    `(let [begin-id# (keyword (name ~id) "begin")
           _# (flex.trace/observe begin-id#)
           v# ~expr]
       (flex.trace/measure ~id begin-id#)
       v#)
    ;; clj
    expr))
