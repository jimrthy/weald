(ns frereth-weald
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
