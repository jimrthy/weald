(def project 'com.frereth.weald)
(def version "0.0.1-SNAPSHOT")

(set-env! :resource-paths #{"src"}
          :dependencies '[[adzerk/boot-cljs "2.1.5" :scope "test"]
                          [adzerk/boot-test "RELEASE" :scope "test"]
                          [binaryage/devtools "0.9.10" :scope "test" :exclusions [org.clojure/tools.reader]]
                          [crisptrutski/boot-cljs-test "0.3.4" :scope "test"]
                          ;; Q: Which features of 1.9.0 does this really need?
                          ;; A: spec
                          [org.clojure/clojure "1.9.0" :exclusions [org.clojure/spec.alpha] :scope "provided"]
                          [org.clojure/clojurescript "1.9.946" :scope "provided" :exclusions [org.clojure/clojure]]
                          [org.clojure/core.async "0.4.474" :exclusions [org.clojure/clojure
                                                                         org.clojure/tools.reader]]
                          [org.clojure/spec.alpha "0.2.176"]
                          ;; FIXME: Move this to the testing task.
                          ;; Don't want to depend on it in general.
                          [org.clojure/test.check "0.10.0-alpha3" :scope "test" :exclusions [org.clojure/clojure]]
                          ;; TODO: Move this into the dev task
                          ;; (sadly, it isn't a straight copy/paste)
                          [samestep/boot-refresh "0.1.0" :scope "test" :exclusions [org.clojure/clojure]]
                          [tolitius/boot-check "0.1.11" :scope "test" :exclusions [org.clojure/clojure]]])

(require '[adzerk.boot-cljs :refer [cljs]]
         '[adzerk.boot-test :refer [test]]
         '[boot.pod :as pod]
         '[crisptrutski.boot-cljs-test :refer [test-cljs]]
         '[samestep.boot-refresh :refer [refresh]]
         '[tolitius.boot-check :as check])

(task-options!
 aot {:namespace   #{'frereth-cp.server 'frereth-cp.client}}
 jar {:file        (str "frereth-weald-" version "-standalone.jar")}
 pom {:project     project
      :version     version
      :description "Functional logging"
      ;; TODO: Add a real website
      :url         "https://github.com/jimrthy/weald"
      :scm         {:url "https://github.com/jimrthy/weald"}
      ;; Q: Should this go into public domain like the rest
      ;; of the pieces?
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}}
 test-cljs {:js-env :node})

(deftask build
  "Build the project locally as a JAR."
  [d dir PATH #{str} "the set of directories to write to (target)."]
  ;; Note that this approach passes the raw command-line parameters
  ;; to -main, as opposed to what happens with `boot run`
  ;; TODO: Eliminate this discrepancy
  (let [dir (if (seq dir) dir #{"target"})]
    (comp (aot)
          ;; FIXME: These should probably be the release versions instead
          (cljs :ids #{"js/node-dev"})
          (cljs :ids #{"js/browser-dev"})
          (pom)
          (jar)
          (target :dir dir))))

(deftask check-conflicts
  "Verify there are no dependency conflicts."
  []
  (with-pass-thru fs
    (require '[boot.pedantic :as pedant])
    (let [dep-conflicts (resolve 'pedant/dep-conflicts)]
      (if-let [conflicts (not-empty (dep-conflicts pod/env))]
        (throw (ex-info (str "Unresolved dependency conflicts. "
                             "Use :exclusions to resolve them!")
                        conflicts))
        (println "\nVerified there are no dependency conflicts.")))))

(deftask dev
  "Add the dev resources to the mix"
  []
  (merge-env! :source-paths #{"dev"})
  identity)

(deftask testing
  "Add pieces for testing"
  []
  (merge-env! :dependencies '[[gloss "0.2.6"
                               :scope "test"
                               :exclusions [byte-streams
                                            io.aleph/dirigiste
                                            manifold
                                            org.clojure/tools.logging
                                            potemkin
                                            riddley]]]
              :source-paths #{"test"})
  identity)

(deftask cider-repl
  "Set up a REPL for connecting from CIDER"
  [p port PORT int]
  ;; Just because I'm prone to forget one of the vital helper steps
  ;; Note that this would probably make more sense under profile.boot.
  ;; Except that doesn't have access to the defined in here, such
  ;; as...well, almost any of what it actually uses.
  ;; Q: Should they move to there also?
  (let [port (or port 32767)]
    (comp (dev) (testing) (check-conflicts) (cider) (javac) (repl :port port :bind "0.0.0.0"))))

(deftask run
  "Run the project."
  [f file FILENAME #{str} "Application arguments passed to main."]
  ;; This is a leftover template from another project that I
  ;; really just copy/pasted over.
  ;; Q: Does it make any sense to keep it around?
  (require '[frereth-cp.server :as app])
  (apply (resolve 'app/-main) file))
