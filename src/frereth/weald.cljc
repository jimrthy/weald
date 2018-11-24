(ns frereth.weald
  "This is really for the sake of shared specs"
  (:require [#?(:clj clojure.spec.alpha
                :cljs cljs.spec.alpha) :as s]))

(s/def ::ctx-atom (s/or :int int?
                        :keyword keyword?
                        :string string?
                        :symbol symbol?
                        :uuid uuid?))
(s/def ::ctx-seq (s/coll-of ::ctx-atom))
(s/def ::context (s/or :atom ::ctx-atom
                       :seq ::ctx-seq))

(s/def ::label keyword?)

;; Note that these next two are *totally* distinct
;; Go with milliseconds since epoch
(s/def ::time nat-int?)
;;; Honestly, this doesn't belong in here.
;;; I can't add a clock to the CurveCP protocol
;;; without breaking it (which doesn't seem like
;;; a terrible idea), but I can make it easily
;;; available to implementers.
;;; For that matter, I might be able to shove one
;;; into the
;;; the zero-padding bytes of each Message.
(s/def ::lamport nat-int?)

(def log-level-values
  "name -> priority to assist in prioritizing and filtering"
  {::trace 100
   ::debug 200
   ::info 300
   ::warn 400
   ::error 500
   ::exception 600
   ::fatal 700})
(def log-levels (set (keys log-level-values)))
(s/def ::level log-levels)

(s/def ::message string?)

(s/def ::details any?)

(s/def ::entry (s/keys :req [::level
                             ::label
                             ::lamport
                             ::time
                             ::message]
                       :opt [::details]))

(s/def ::entries (s/coll-of ::entry))

(s/def ::state (s/keys :req [::context
                             ::entries
                             ::lamport]))

;;;; Implement this for your side-effects
(defprotocol Logger
  "Extend this for logging side-effects"
  (log! [this msg]
    "At least queue up a log message to side-effect")
  ;; It's tempting to add things like filtering to do things like
  ;; discarding all logs except warn and error.
  ;; Don't give in to temptation: keep the concerns separated.
  ;; Honestly, that's something that should probably be stateful
  ;; in a serious system, so operators can modify logging levels
  ;; on the fly based on whether or not something's going wrong.
  ;; For that matter, it would be nice to use something like anomaly
  ;; detectiong to handle those changes automatically.
  (flush! [this] "Some loggers need to do this at the end of a batch"))
(s/def ::logger #(satisfies? Logger %))
