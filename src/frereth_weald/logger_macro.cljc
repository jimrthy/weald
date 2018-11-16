(ns frereth-weald.logger-macro
  "Note that this is really for the sake of cljs"
  ;; I need to run clojurescript code inside here.
  ;; Q: How does this work out?
  (:require [#? (:clj clojure.spec.alpha
                 :cljs cljs.spec.alpha) :as s]
            [#?(:clj clojure.stacktrace
                :cljs cljs.stacktrace) :as s-t]))

#?(:clj (s/fdef get-current-thread
          :ret string?))
#?(:clj (defn get-current-thread
          []
          (.getName (Thread/currentThread))))


#?(:clj (defn build-log-entry
          ([label lamport level]
           {::current-thread (get-current-thread)
            ::label label
            ::lamport lamport
            ::level level
            ::time (System/currentTimeMillis)})
          ([label lamport level message]
           (assoc (build-log-entry label lamport level)
                  ::message message))))

#?(:cljs (defn build-log-entry
           ([label lamport level]
            {::label label
             ::lamport lamport
             ::level level
             ::time (.now js/Date)})
           ([label lamport level message]
            (assoc (build-log-entry label lamport level)
                   ::message message))))

(s/fdef add-log-entry
        :args (s/cat :log-state :frereth-weald.logging/state
                     :level :frereth-weald.logging/level
                     :label :frereth-weald.logging/label
                     :message :frereth-weald.logging/message
                     :details :frereth-weald.logging/details)
        :ret :frereth-weald.logging/entries)
#?(:clj (defn add-log-entry
          ([{:keys [::lamport]
             :as log-state}
            level
            label]
           (when-not lamport
             (let [ex (ex-info "Desperation warning: missing clock among"
                               {::problem (or log-state "falsey log-state")})]
               (s-t/print-stack-trace ex)))
           (-> log-state
               (update
                ::entries
                conj
                (build-log-entry label lamport level))
               (update ::lamport inc)))
          ([{:keys [::lamport]
             :as log-state}
            level
            label
            message]
           (when-not lamport
             (let [ex (ex-info "Desperation warning: missing clock among" (or {::problem log-state}
                                                                              {::problem "falsey log-state"}))]
               (s-t/print-stack-trace ex)))
           (-> log-state
               (update
                ::entries
                conj
                (build-log-entry label lamport level message))
               (update ::lamport inc)))
          ([{:keys [::context
                    ::lamport]
             :as log-state}
            level
            label
            message
            details]
           (-> log-state
               (add-log-entry level label message)
               (update ::entries
                       (fn [cur]
                         (assoc-in cur
                                   [(dec (count cur))
                                    ::details]
                                   details))))))

   :cljs (defn add-log-entry
           ([{:keys [::lamport]
              :as log-state}
             level
             label]
            (when-not lamport
              (let [ex (ex-info "Desperation warning: missing clock among"
                                {::problem (or log-state "falsey log-state")})]
                (console.log (repl/print-mapped-stacktrace (.-stack ex)))))
            (-> log-state
                (update
                 ::entries
                 conj
                 (build-log-entry label lamport level))
                (update ::lamport inc)))
           ([{:keys [::lamport]
              :as log-state}
             level
             label
             message]
            (when-not lamport
              (let [ex (ex-info "Desperation warning: missing clock among" (or {::problem log-state}
                                                                               {::problem "falsey log-state"}))]
                (console.log (repl/print-mapped-stacktrace (.-stack ex)))))
            (-> log-state
                (update
                 ::entries
                 conj
                 (build-log-entry label lamport level message))
                (update ::lamport inc)))
           ([{:keys [::context
                     ::lamport]
              :as log-state}
             level
             label
             message
             details]
            (-> log-state
                (add-log-entry level label message)
                (update ::entries
                        (fn [cur]
                          (assoc-in cur
                                    [(dec (count cur))
                                     ::details]
                                    details)))))))

(defmacro deflogger
     [level]
     ;; TODO: I'd much rather do something like this for the sake of hygiene:
     (comment
       `(let [lvl-holder# '~level
              tag-holder# (keyword (str *ns*) (name lvl-holder#))]
          (defn '~lvl-holder#
            ([entries#
              label#
              message#
              details#]
             (add-log-entry entries# ~'~tag-holder label# message# details#))
            ([entries#
              label#
              message#]
             (add-log-entry entries# ~'~tag-holder label# message#)))))
     (let [tag (keyword (str *ns*) (name level))]
       ;; The auto-gensymmed parameter names are obnoxious.
       ;; And largely irrelevant.
       ;; This isn't the kind of macro that you nest inside
       ;; other macros.
       ;; Then again...auto-namespacing makes eliminating them
       ;; interesting.
       `(defn ~level
          ;; TODO: Refactor the parameter order.
          ;; It doesn't seem like it should ever be worth it, but a
          ;; complex function might save some code by setting up
          ;; partial(s) using the label
          ([log-state#
            label#
            message#
            details#]
           (add-log-entry log-state# ~tag label# message# details#))
          ([log-state#
            label#
            message#]
           (add-log-entry log-state# ~tag label# message#))
          ([log-state#
            label#]
           (add-log-entry log-state# ~tag label#)))))
