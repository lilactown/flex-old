(ns flex.test-macros
  (:require
   [clojure.core.async :as a :include-macros true]
   #?(:cljs [cljs.core.async.interop :as ai])
   [clojure.test :as t :include-macros true])
  #?(:cljs (:require-macros [flex.test-macros])))

(defmacro async-test
  [& body]
  (if (some? (:ns &env))
    `(cljs.test/async done# (cljs.core.async/go ~@body (done#))) ;; CLJS
    `(do ~@body)))


(defmacro <<
  [p]
  (if (some? (:ns &env))
    `(cljs.core.async.interop/<p! ~p)
    `(deref ~p)))
