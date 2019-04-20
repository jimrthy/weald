(ns frereth.weald.specs
  "This is really for the sake of shared specs"
  (:require [#?(:clj clojure.spec.alpha
                :cljs cljs.spec.alpha) :as s]))

;;; Note that this is pretty generally useful
(s/def ::atom #(instance? (type (atom nil)) %))

;; This is based on the idea of an atom from common lisp rather than the
;; clojure state management construct
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

(s/def ::state-atom (s/and ::atom
                           #(s/valid? ::state (deref %))))
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

;; It's tempting to pass around this instead of a ::weald/logger
;; instance directly.
;; To create Logger instances on demand and then discard them.
;; The temptation seems dumb.
;; Q: So why am I still tempted?
(s/def ::log-builder (s/fspec :args nil
                              :ret ::logger))
