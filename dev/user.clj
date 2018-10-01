(ns user
  "Pull in generally useful REPL requirements"
  (:require [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.repl :refer (apropos dir doc pst root-cause source)]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as test]
            [clojure.test.check :refer (quick-check)]
            [clojure.test.check.clojure-test :refer (defspec)]
            [clojure.test.check.generators :as lo-gen]
            [clojure.test.check.properties :as props]
            [clojure.test.check.generators :as lo-gen]
            ;; This is moderately useless under boot.
            [clojure.tools.namespace.repl :refer (refresh refresh-all)]
            [frereth-weald.logging :as log]))
