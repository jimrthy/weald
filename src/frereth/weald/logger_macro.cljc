(ns frereth.weald.logger-macro
  "Note that this is really for the sake of cljs"
  ;; I need to run clojurescript code inside here.
  ;; Q: How does this work out?
  (:require #?(:cljs [cljs.repl :as repl])
            [#? (:clj clojure.spec.alpha
                 :cljs cljs.spec.alpha) :as s]
            [#?(:clj clojure.stacktrace
                :cljs cljs.stacktrace) :as s-t]
            [frereth.weald.specs :as weald]))

#?(:clj (s/fdef get-current-thread
          :ret string?))
#?(:clj (defn get-current-thread
          []
          (.getName (Thread/currentThread))))

(s/fdef build-log-entry
  :args (s/cat :label ::weald/label
               :lamport ::weald/lamport
               :level ::weald/level)
  :ret ::weald/entry)
#?(:clj (defn build-log-entry
          ([label lamport level]
           {::weald/current-thread (get-current-thread)
            ::weald/label label
            ::weald/lamport lamport
            ::weald/level level
            ::weald/time (System/currentTimeMillis)})
          ([label lamport level message]
           (assoc (build-log-entry label lamport level)
                  ::weald/message message))))

#?(:cljs (defn build-log-entry
           ([label lamport level]
            {::weald/label label
             ::weald/lamport lamport
             ::weald/level level
             ::weald/time (.now js/Date)})
           ([label lamport level message]
            (assoc (build-log-entry label lamport level)
                   ::weald/message message))))

(s/fdef add-log-entry
        :args (s/cat :log-state ::weald/state
                     :level ::weald/level
                     :label ::weald/label
                     :message ::weald/message
                     :details ::weald/details)
        :ret ::weald/entries)
#?(:clj (defn add-log-entry
          ([{:keys [::weald/lamport]
             :as log-state}
            level
            label]
           (when-not lamport
             (let [ex (ex-info "Desperation warning: missing clock among"
                               {::problem (or log-state "falsey log-state")})]
               (s-t/print-stack-trace ex)))
           (-> log-state
               (update
                ::weald/entries
                conj
                (build-log-entry label lamport level))
               (update ::weald/lamport inc)))
          ([{:keys [::weald/lamport]
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
                ::weald/entries
                conj
                (build-log-entry label lamport level message))
               (update ::weald/lamport inc)))
          ([{:keys [::weald/context
                    ::weald/lamport]
             :as log-state}
            level
            label
            message
            details]
           (-> log-state
               (add-log-entry level label message)
               (update ::weald/entries
                       (fn [cur]
                         (assoc-in cur
                                   [(dec (count cur))
                                    ::weald/details]
                                   details))))))

   :cljs (defn add-log-entry
           ([{:keys [::weald/lamport]
              :as log-state}
             level
             label]
            (when-not lamport
              (let [ex (ex-info "Desperation warning: missing clock among"
                                {::problem (or log-state "falsey log-state")})]
                (console.log (s-t/mapped-stacktrace-str (.-stack ex) {}))))
            (-> log-state
                (update
                 ::weald/entries
                 conj
                 (build-log-entry label lamport level))
                (update ::weald/lamport inc)))
           ([{:keys [::weald/lamport]
              :as log-state}
             level
             label
             message]
            (when-not lamport
              (let [ex (ex-info "Desperation warning: missing clock among" (if log-state
                                                                             {::problem log-state}
                                                                             {::problem "falsey log-state"}))]
                (console.log (s-t/mapped-stacktrace-str (.-stack ex) {}))))
            (-> log-state
                (update
                 ::weald/entries
                 conj
                 (build-log-entry label lamport level message))
                (update ::weald/lamport inc)))
           ([{:keys [::weald/context
                     ::weald/lamport]
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
                                     ::weald/details]
                                    details)))))))

(defmacro deflogger
     [level]
     ;; TODO: I'd much rather do something like this for the sake of hygiene:
     (comment
       `(let [lvl-holder# '~level
              tag-holder# (keyword "frereth.weald.specs" (name lvl-holder#))]
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
     (let [tag (keyword "frereth.weald.specs" (name level))]
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
