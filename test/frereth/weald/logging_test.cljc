(ns frereth.weald.logging-test
  (:require
   #?(:clj [clojure.core.async
            :as async
            :refer [go]]
      :cljs [cljs.core.async :as async])
   #?(:clj [clojure.spec.test.alpha :as test]
      :cljs [cljs.spec.test.alpha :as test])
   [#?(:clj clojure.stacktrace
       :cljs cljs.stacktrace) :as s-t]
   [#?(:clj clojure.test
       :cljs cljs.test) #?(:clj :refer
                           :cljs :refer-macros) [deftest is testing]]
   [frereth.weald.specs :as weald]
   [frereth.weald.logging :as log])
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go]]))
  #?(:clj (:import clojure.lang.ExceptionInfo)))

#?(:cljs (enable-console-print!))

(defn set-up
  [ctx]
  (-> (log/init ctx 0)
      (log/debug ::set-up "Entry 1")
      (log/debug ::set-up "Entry 2")))

(deftest check-fork
  (let [log-1 (set-up ::check-fork)
        [log-1 log-2] (log/fork log-1 ::forked)]

    (is (= (::weald/lamport log-1)
           (::weald/lamport log-2)))
    (let [forked-entries (::weald/entries log-2)]
      (is (= (count forked-entries) 1)))))

(comment
  (deftest merge-entries
    ;; This is a slow test, so it's probably worth spinning off into
    ;; a folder that's really only meant for pre-commit testing.
    (let [raw (test/check `log/merge-entries)
          result (get-in (first raw) [#?(:clj :clojure.spec.test.check/ret
                                         :cljs :clojure.test.check/ret)
                                      ;; Q: Would pass? be a better key to check?
                                      :result])]
      (is result)
      (when (not result)
        (throw (ex-info "" {::failure raw}))))))

(deftest async-logger
  (try
    (let [ch (async/chan)
          logger (log/async-log-factory ch)
          handler (go (loop [n 0
                             entries []]
                        (let [[entry rcvd-port] (async/alts! [ch (async/timeout 100)])]
                          (if (and entry
                                   (= ch rcvd-port))
                            (recur (inc n) (conj entries entry))
                            [n entries]))))
          expected 10
          place-holder (atom {::fulfilled false})
          log-state (loop [n 0
                           log-state (log/init ::async-log)]
                      (if (< n expected)
                        (recur (inc n)
                               (log/info log-state ::whatever "" n))
                        log-state))]
      (add-watch place-holder ::test-done
                 (fn [_k _atom old-state
                      {:keys [::fulfilled ::result]
                       :as new-state}]
                   (println "Test updated: " result)
                   (is fulfilled)
                   (is result)
                   (remove-watch _atom _k)))
      (log/flush-logs! logger log-state)
      (async/close! ch)
      (let [checker (go (let [[response port] (async/alts! [handler (async/timeout 250)])]
                          (if (= port handler)
                            (let [[actual entries] response]
                              ;; There's an extra trace message about the flush!
                              (is (= (inc expected) actual))
                              (is (= (inc expected) (count entries)))
                              (let [range-expected (range expected)
                                    entries-expected (take expected (map ::weald/details entries))]
                                (when (not= range-expected entries-expected)
                                  (println "Mismatch:\n" range-expected
                                           "\nnot=" entries-expected
                                           "\nExtracted from\n" entries))
                                ;; Q: How on Earth is this passing?
                                (is (= range-expected entries-expected)))
                              (if (and (= actual (inc expected))
                                       (= (count entries) (inc expected))
                                       (= (take expected (map ::weald/details entries)) (range expected)))
                                (reset! place-holder {::fulfilled true
                                                      ::result true})
                                (reset! place-holder {::fulfilled true
                                                      ::result false})))
                            (is (= port handler)))))]
        ;; Q: How can we wait on an async result in cljs?
        ;; A: We can't. At least not on the browser.
        (is checker)))
    (catch #?(:clj Exception :cljs :default) ex
      (try
        (let [stack-trace #?(:clj (with-out-str (s-t/print-stack-trace ex))
                             ;; This isn't going to work out. Stacktrace
                             ;; must be canonicalized.
                             ;; The second param is "a map of library names to
                             ;; decoded source maps."
                             :cljs (s-t/mapped-stacktrace-str (.-stack ex) {}))
              base {::stack stack-trace
                    ::exception ex}
              with-details (if (instance? ExceptionInfo ex)
                             (assoc-in base [::data ::problem] (ex-data ex))
                             base)]
          (if-let [cause #?(:clj (.getCause ex)
                            :cljs (ex-cause ex))]
            (assoc with-details ::cause (log/exception-details cause))
            (println with-details)))
        #?(:clj (catch Exception ex1
                  (println "Inner exception" ex1 "triggered by trying to handle" ex1))
           :cljs (catch :default ex1
                   (println "Inner exception" ex1 "triggered by trying to handle" ex1))))
      (throw ex))))
