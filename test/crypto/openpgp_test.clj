(ns crypto.openpgp-test
  (:use clojure.test crypto.openpgp))

(deftest a-test
  (testing "library-version"
    (is (re-matches #"(?>0|[1-9][0-9]*)\.(?>0|[1-9][0-9]*)\.(?>0|[1-9][0-9]*)" (library-version)))))
