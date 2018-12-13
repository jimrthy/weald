(def project-name "com.frereth/weald")

(require '[clojure.java.shell :as sh])

(defn next-version [version]
  (when version
    (let [[a b] (next (re-matches #"(.*?)([\d]+)" version))]
      (when (and a b)
        (str a (inc (Long/parseLong b)))))))

(def default-version
  "Really just for running inside docker w/out git tags"
  "0.0.5-???-dirty")
(defn deduce-version-from-git
  "Avoid another decade of pointless, unnecessary and error-prone
  fiddling with version labels in source code.

  Important note: this only works if your repo has tags!
  And the tags this cares about need to be numeric. Can't
  use, e.g. 0.0.1-SNAPSHOT.

  Another interesting detail is that tags must have commit
  messages for describe to work properly:
  `git tag 0.0.2 -m 'Move forward'`"
  []
  (let [[version previous-hash commits hash dirty?]
        (next (re-matches #"(\d+\.\d+\.\d+)(-\w*)?-(\d*)-(.*?)(-dirty)?\n"
                          (:out (sh/sh "git"
                                       "describe"
                                       "--always"
                                       "--dirty"
                                       "--long"
                                       "--tags"
                                       "--match" "[0-9].*"))))]
    (if commits
      (cond
        dirty? (str (next-version version) "-" hash "-dirty")
        (pos? (Long/parseLong commits)) (str (next-version version) "-" hash)
        :otherwise version)
      default-version)))
(def project 'com.frereth/weald)
(def version #_"0.0.5-SNAPSHOT" (deduce-version-from-git))

(set-env! :resource-paths #{"src"}
          :dependencies '[[adzerk/bootlaces "0.1.13" :scope "test"]
                          [adzerk/boot-cljs "2.1.5" :scope "test"]
                          [adzerk/boot-test "RELEASE" :scope "test"]
                          [binaryage/devtools "0.9.10" :scope "test" :exclusions [org.clojure/tools.reader]]
                          [crisptrutski/boot-cljs-test "0.3.4" :scope "test"]
                          ;; Q: Which features of 1.9.0 does this really need?
                          ;; A: spec
                          [org.clojure/clojure "1.9.0" :exclusions [org.clojure/spec.alpha] :scope "provided"]
                          ;; This supplies org.clojure/tools.reader.
                          ;; But in a "provided" scope.
                          ;; As-is, anything that uses this library will have to supply that.
                          ;; Which is probably fine, if they're using clojurescript.
                          ;; But annoying for clojure projects which really shouldn't need it.
                          ;; Q: Which approach is worse?
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

(require '[adzerk.bootlaces :refer [bootlaces! build-jar push-snapshot push-release]]
         '[adzerk.boot-cljs :refer [cljs]]
         '[adzerk.boot-test :refer [test]]
         '[boot.pod :as pod]
         '[crisptrutski.boot-cljs-test :refer [test-cljs]]
         '[samestep.boot-refresh :refer [refresh]]
         '[tolitius.boot-check :as check])

(task-options!
 aot {:namespace   #{'frereth-cp.server 'frereth-cp.client}}
 jar {:file        (str "frereth-weald-" version ".jar")}
 pom {:project     project
      :version     version
      :description "Functional logging"
      :url         "https://github.com/jimrthy/weald"
      :scm         {:url "https://github.com/jimrthy/weald"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}}
 test-cljs {:js-env :node})

(deftask set-version
  []
  (let [version (deduce-version-from-git)]
    (task-options!
     jar {:file (str "frereth-cp-" version ".jar")}
     pom {:version version})
    (bootlaces! version :dont-modify-paths? true))
  identity)

(deftask build
  "Build the project locally as a JAR."
  [d dir PATH #{str} "the set of directories to write to (target)."]
  (let [dir (if (seq dir) dir #{"target"})]
    (comp (set-version)
          (aot)
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

(deftask to-clojars
  "Publish to clojars from your current branch"
  []
  (task-options! push {:ensure-branch nil})
  (comp (set-version) (build-jar) (push-release)))
