(ns frereth.weald.logging
  "Functional logging mechanism"
  (:require #?(:clj [clojure.core.async
                     :as async
                     :refer [go]]
               :cljs [cljs.core.async :as async])
   #?(:clj [clojure.java.io :as io])
   #?(:cljs [cljs.repl :as repl])
   [#?(:clj clojure.spec.alpha
       :cljs cljs.spec.alpha) :as s]
   [#?(:clj clojure.stacktrace
       :cljs cljs.stacktrace) :as s-t]
   [clojure.string :as str]
   [frereth.weald.logger-macro :refer [#?(:clj deflogger)
                                       add-log-entry]]
   [frereth.weald.specs :as weald #?@(:cljs (:refer [Logger log!]))])
  #?(:clj (:import clojure.lang.ExceptionInfo
                  [java.io
                   BufferedWriter
                   FileWriter
                   OutputStream
                   OutputStreamWriter]))
  #?(:cljs (:require-macros
            [cljs.core.async.macros :refer [go]]
            [frereth.weald.logger-macro :refer [deflogger]]))
  #?(:clj (:import frereth.weald.specs.Logger)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal

(defn exception-details
  [ex]
  (if (instance? #?(:clj Throwable :cljs js/Error) ex)
    ;; Q: What should this look like in cljs?
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
          (assoc with-details ::cause (exception-details cause))
          with-details))
      #?(:clj (catch Throwable ex1
                {::exception ex
                 ::exception-class (class ex)
                 ::insult-to-injury {::exception ex1
                                     ::stack (s-t/print-stack-trace ex)}})
         :cljs (catch :default ex1
                 {::exception ex
                  ::exception-class (type ex)
                  ::insult-to-injury {::exception ex1
                                      ::stack (s-t/mapped-stacktrace-str (.-stack ex) {})}})))
    {::non-exception ex
     ::non-exception-class (#?(:clj class
                               :cljs type) ex)}))

(declare init)
(defn format-log-string
  [caller-stack-holder entry]
  (try
    (prn-str entry)
    (catch #?(:clj RuntimeException :cljs js/Error) ex
      (let [injected
            (prn-str (add-log-entry (init ::logging-formatter -1)
                                    ::exception
                                    ::log!
                                    "Failed to write a log"
                                    {::immediate (exception-details ex)
                                     ::caller (exception-details caller-stack-holder)
                                     ::redacted-problem (prn-str (dissoc entry ::weald/details))}))]
        ;; Q: What's the best thing to do here?
        (comment (throw ex))
        injected))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Default implementations

(defrecord AsyncLogger [channel state-atom]
  Logger
  (log! [_ entry]
    (async/put! channel entry))
  (flush! [_]
    (swap! state-atom #(update % ::flush-count inc))))

(defrecord CompositeWriter
    [subordinates]
  ;; Q: Would this be any more efficient to implement using
  ;; something like a transducer?
  ;; Note that it isn't intended for combining lots and lots
  ;; of loggers. This is more for scenarios like the ones where
  ;; a. Most logs go to a traditional logging system
  ;; b. They all go somewhere cheap and painful for post-mortems
  ;; c. High-priority logs send out emails
  ;; d. Really nasty ones trigger pager notifications
  ;; This doesn't replace serious monitoring/notification systems
  ;; like PagerDuty, but it can be a valuable supplement.
  Logger
  (log! [_ entry]
    (doseq [sub subordinates]
      (.log! sub entry)))
  (flush! [_]
    (doseq [sub subordinates]
      (.flush! sub))))

#?(:cljs
    (defrecord ConsoleLogger [state-atom]
      Logger
      (log! [_ entry]
        (let [func
              (condp = (::weald/label entry)
                ::weald/trace (partial console.debug "TRACE: ")
                ::weald/debug console.debug
                ::weald/info console.log
                ::weald/warn console.warn
                ::weald/error console.error
                ::weald/exception (partial console.error "EXCEPTION: ")
                ::weald/fatal (partial console.error "FATAL: "))]
          (func entry)))
      (flush! [_]
        (swap! state-atom #(update % ::flush-count inc)))))

#?(:clj
    (defrecord OutputWriterLogger [writer]
      Logger
      ;; According to stackoverflow (and the java source
      ;; code that was provided as evidence), BufferedWriter
      ;; .write and .flush are both synchronized and thus
      ;; safe to use from multiple threads at once.
      (log! [{writer :writer
              :as this}
             entry]
        (let [get-caller-stack (RuntimeException. "Q: Is there a cheaper way to get the call stack?")
              formatted (format-log-string get-caller-stack entry)]
          (.write writer formatted)))
      (flush! [{^BufferedWriter writer :writer
                :as this}]
        (.flush writer))))

#?(:clj (defrecord StreamLogger [stream]
          ;; I think this is mostly correct,
          ;; but I haven't actually tried testing it
          ;; And, realistically, contrasted with
          ;; OutputWriterLogger, this should probably
          ;; never be used.
          Logger
          (log! [{^OutputStream stream :stream
                  :as this}
                 entry]
            (let [get-caller-stack (RuntimeException. "Q: Is there a cheaper way to get the call stack?")
                  formatted (format-log-string get-caller-stack entry)]
              (.write stream formatted)))
          (flush! [{^OutputStream stream :stream
                    :as this}]
            (.flush stream))))

#?(:clj (defrecord StdErrLogger [state-agent]
          ;; Really just a StreamLogger
          ;; where stream is STDOUT.
          ;; But it's simple/easy enough that it seemed
          ;; worth writing this way instead
          Logger
          (log! [{:keys [:state-agent]
                  :as this} msg]
            (binding [*out* *err*]
              (when-let [ex (agent-error state-agent)]
                (println "Logging Agent Failed:\n"
                         (exception-details ex))
                ;; Q: What are the odds this will work?
                (let [last-state @state-agent]
                  (println "Logging Agent State:\n"
                           last-state)
                  (restart-agent state-agent last-state)))
              ;; Creating an exception that we're going to throw away
              ;; for almost every log message seems really wasteful.
              (let [get-caller-stack (RuntimeException. "Q: Is there a cheaper way to get the call stack?")]
                (send state-agent (fn [state entry]
                                    (print (format-log-string get-caller-stack entry))
                                    state)
                      msg))))
          ;; Q: Is there any point to calling .flush
          ;; on STDOUT?
          ;; A: Not according to stackoverflow.
          ;; It flushes itself after every CR/LF
          (flush! [_]
            (send state-agent #(update % ::flush-count inc)))))

#?(:clj (defrecord StdOutLogger [state-agent]
          ;; Really just a StreamLogger
          ;; where stream is STDOUT.
          ;; But it's simple/easy enough that it seemed
          ;; worth writing this way instead
          Logger
          (log! [{:keys [:state-agent]
                  :as this} msg]
            (when-let [ex (agent-error state-agent)]
              (println "Logging Agent Failed:\n"
                       (exception-details ex))
              (let [last-state @state-agent]
                (println "Logging Agent State:\n"
                         last-state)
                (restart-agent state-agent last-state)))
            ;; Creating an exception that we're going to throw away
            ;; for almost every log message seems really wasteful.
            (let [get-caller-stack (RuntimeException. "Q: Is there a cheaper way to get the call stack?")]
              (send state-agent (fn [state entry]
                                  (print (format-log-string get-caller-stack entry))
                                  state)
                    msg)))
          ;; Q: Is there any point to calling .flush
          ;; on STDOUT?
          ;; A: Not according to stackoverflow.
          ;; It flushes itself after every CR/LF
          (flush! [_]
            (send state-agent #(update % ::flush-count inc)))))

(s/fdef merge-entries
        :args (s/cat :xs ::weald/entries
                     :ys ::weald/entries)
        :fn (fn [{:keys [:args :ret]}]
              (let [{:keys [:xs :ys]} args
                    combined (distinct (concat xs ys))]
                (= (count combined)
                   (count ret))))
        :ret ::weald/entries)
(defn merge-entries
  "Note that this is a relatively expensive operation"
  [xs ys]
  ;; This is something that's fairly trivial with
  ;; c++ and iterators.
  ;; Note that concatenating vectors is probably the
  ;; expensive part. I'm pretty sure it's O(n).
  ;; TODO: Try experimenting with
  ;; a) converting to lists
  ;; b) running concat
  ;; c) doing the sort
  ;; d) converting back to vectors
  ;; Then again, if you're letting your logs build deeply
  ;; enough in memory between flushes that this matters,
  ;; you probably have other problems.
  (let [result (concat xs ys)]
    (vec (distinct (sort (fn [x y]
                           (compare [(::weald/lamport x) (::weald/time x) (weald/log-level-values (::weald/level x)) (::weald/message x)]
                                    [(::weald/lamport y) (::weald/time y) (weald/log-level-values (::weald/level y)) (::weald/message y)]))
                         result)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

;; It would be nice to just do this by looping over the
;; keys in weald/log-level-values for the sake of DRY.
;; But doing that involves eval, which isn't usually
;; available in clojurescript.
(deflogger trace)
(deflogger debug)
(deflogger info)
(deflogger warn)
(deflogger error)
(deflogger fatal)

(defn exception
  ([log-state ex label]
   (add-log-entry log-state ::exception label ""
                  (exception-details ex)))
  ([log-state ex label message]
   (add-log-entry log-state ::exception label message
                  (exception-details ex)))
  ([log-state ex label message original-details]
   (let [details {::original-details original-details
                  ::problem (exception-details ex)}]
     (add-log-entry log-state ::exception label message details))))

(declare get-official-clock)
(s/fdef init
  :args (s/or :time-zero (s/cat :context ::weald/context)
              :existing-clock (s/cat :context ::weald/context
                                     :start-time ::weald/lamport))
        :ret ::weald/state)
(defn init
  ([context start-clock]
   {::weald/entries []
    ::weald/lamport start-clock
    ::weald/context context})
  ([context]
   (init context (get-official-clock))))

;;; Q: Would it make sense to convert these various factory calls to a
;;; single multi-method?
(defn async-log-factory
  [ch]
  (->AsyncLogger ch (atom {::flush-count 0})))

(s/fdef composite-log-factory
  :args (s/cat :loggers-to-combine (s/coll-of ::weald/logger))
  :ret ::weald/logger)
(defn composite-log-factory
  [loggers-to-combine]
  (->CompositeWriter loggers-to-combine))

#?(:cljs (defn console-log-factory
           []
           (->ConsoleLogger (atom {::flush-count 0}))))

#?(:clj (defn file-writer-factory
          [file-name]
          (io/make-parents file-name)
          (let [writer (BufferedWriter. (FileWriter. file-name))]
            (->OutputWriterLogger writer))))

#?(:clj (defn std-out-log-factory
          []
          ;; Q: Do agents really make sense here?
          (->StdOutLogger (agent {::flush-count 0}))))

#?(:clj (defn std-err-log-factory
          []
          (->StdErrLogger (agent {::flush-count 0}))))

#?(:clj (defn stream-log-factory
          [stream]
          (->StreamLogger stream)))

;; Do what I can to keep local clocks synchronized
;; TODO: Allow external libraries to initialize this.
;; It would greatly simplify sharing.
;; As long as I'm breaking things anyway, might as
;; well add a requirement to initialize this.
;; It's also tempting to make this a Protocol.
(let [my-lamport (atom 0)]
  (defn get-official-clock
    []
    @my-lamport)
  ;; Just for debugging and REPL development
  (comment (get-official-clock))

  ;; WARNING:
  ;; This is changing in a breaking way. Make sure the change
  ;; propagates!
  (s/fdef do-sync-clock
          :args (s/cat :remote-clock ::weald/lamport)
          :ret ::weald/lamport)
  (defn do-sync-clock
    "Synchronize my clock with a state's"
    [remote-lamport]
    (swap! my-lamport max remote-lamport))

  (s/fdef flush-logs!
    :args (s/cat :logger ::weald/logger
                 :logs ::weald/state)
    :ret ::weald/state)
  (defn flush-logs!
    "For the side-effects to write the accumulated logs.

     Returns a fresh set of log entries"
    ;; TODO: Reverse these parameters.
    ;; So I can thread-first log-state through
    ;; log calls into this
    [logger
     {:keys [::weald/context]
      :as log-state}]
    ;; Honestly, there should be an agent that handles this
    ;; so we don't block the calling thread.
    ;; The i/o costs should be quite a bit higher than
    ;; the agent overhead...though
    ;; a go-loop would be more efficient
    (let [{:keys [::weald/lamport]
           :as log-state} (add-log-entry log-state
                                         ::trace
                                         ::top
                                         "flushing"
                                         {::weald/context context})]
      (try
        (doseq [message (::weald/entries log-state)]
          (weald/log! logger message))
        (weald/flush! logger)

        (assoc log-state
               ::weald/log-state (do-sync-clock lamport)
               ::weald/entries [])
        (catch #?(:clj RuntimeException :cljs :default) ex
          (println (str ex "\nTrying to flush logs for\n"
                        logger ", a " (type logger)))
          (throw ex))))))

(s/fdef synchronize
        :args (s/cat :lhs ::weald/state
                     :rhs ::weald/state)
        :fn (s/and #(let [{:keys [:args :ret]} %
                          {:keys [:lhs :rhs]} args]
                      ;; Only changes the lamport tick of the
                      ;; clock states
                      (and (= (-> ret first (dissoc ::weald/lamport))
                              (dissoc lhs ::weald/lamport))
                           (= (-> ret second (dissoc ::weald/lamport))
                              (dissoc rhs ::weald/lamport))))
                   #(let [{:keys [:args :ret]} %
                          {:keys [:lhs :rhs]} args]
                      (= (ret first ::weald/lamport)
                         (ret second ::weald/lamport)
                         (max (::weald/lamport lhs)
                              (::weald/lamport rhs)))))
        ;; Yes. It really is a pair of them.
        :ret (s/tuple ::weald/state ::weald/state))
(defn synchronize
  "Fix 2 clocks that have probably drifted apart"
  [{l-clock ::weald/lamport
    l-ctx ::weald/context
    :as lhs}
   {r-clock ::weald/lamport
    r-ctx ::weald/context
    :as rhs}]
  {:pre [l-clock
         r-clock]}
  (let [synced (max l-clock r-clock)
        lhs (assoc lhs ::weald/lamport synced)
        rhs (assoc rhs ::weald/lamport synced)]
    [(debug lhs ::synchronized "" {::weald/context l-ctx})
     (debug rhs ::synchronized "" {::weald/context r-ctx})]))

(s/fdef clean-fork
        :args (s/cat :source ::weald/state
                     :child-context ::weald/context)
        :ret ::weald/state)
(defn clean-fork
  "Fork the context/lamport clock without the logs.

Main use-case is exception handlers in weird side-effecty places
where it isn't convenient to propagate a log line or 2 that will
show up later."
  [src child-context]
  (let [parent-ctx (::weald/context src)
        combiner (if (seq? parent-ctx)
                   conj
                   list)]
    (init (combiner parent-ctx child-context)
          (inc (::weald/lamport src)))))

(s/fdef fork
        :args (s/or :with-child-ctx (s/cat :source ::weald/state
                                           :child-context ::weald/context)
                    :without-child-ctx (s/cat :source ::weald/state))
        ;; Note that the return value really depends
        ;; on the caller arity.
        ;; TODO: Need to write the :fn value to reflect this.
        :ret (s/or :with-nested-context (s/tuple ::weald/state ::weald/state)
                   :keep-parent-context ::weald/state))
(defn fork
  "Return shape depends on arity"
  ([src child-context]
   (let [parent-ctx (::weald/context src)
         combiner (if (seq? parent-ctx)
                    conj
                    list)
         forked (init (combiner parent-ctx child-context)
                      (::weald/lamport src))]
     (synchronize src forked)))
  ([src]
   (init (::context src) (inc (::weald/lamport src)))))

(s/fdef merge-state
        :args (s/cat :logs1 ::weald/state
                     :logs2 ::weald/state)
        :ret ::weald/state)
(defn merge-state
  "Combine the entries of two log states

  This is mostly meant for logs that have diverged from the same context."
  [x y]
  (let [combined-entries (merge-entries (::weald/entries x) (::weald/entries y))
        result
        ;; There really isn't a good way to pick a winner if this conflicts
        {::weald/context (::weald/context x)
         ::weald/entries combined-entries
         ::weald/lamport (max (::weald/lamport x) (::weald/lamport y))}]
    (debug result ::top "Merged entries")))

(s/fdef log-atomically!
  :args (s/cat :log-atom ::weald/state-atom
               ;; TODO: Need an fspec for this.
               ;; And surely I have one already.
               :log-fn any?
               ;; Q: What's a good way to indicate & rest args?
               :log-args any?)
  :ret any?)
(defn log-atomically!
  "Accumulate log messages into an atom

  This can frequently be more convenient than returning the updated
  state."
  [log-atom log-fn & args]
  (swap! log-atom #(apply log-fn % args)))

(s/fdef flush-atomically!
  :args (s/cat :log-atom ::weald/state-atom
               :logger ::weald/logger)
  :ret any?)
(defn flush-atomically!
  "Flush an accumulation of log messages"
  [logger log-atom]
  (swap! log-atom #(flush-logs! logger %)))
