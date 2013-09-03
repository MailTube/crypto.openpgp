(ns crypto.openpgp-test
  (:use clojure.test crypto.openpgp)
  (:import [java.util Random]))

(defn- working-random []
  (new java.util.Random 1))

; A less uniform distribution.
(defn- broken-random []
  (let [random (working-random)]
    (proxy [java.util.Random] [0]
      (setSeed [seed]
        (.setSeed random seed))
      (next [n]
        (let [r1 (.nextInt random),
              r2 (.nextInt random),
              r3 (.nextInt random),
              r4 (.nextInt random)]
          (bit-and (bit-shift-right (+ r1 r2 r3 r4) 2) (- (bit-shift-left 1 n) 1)))))))

(defn- run-nr-test [random]
  (not (not-random random 64 100000 0.001)))

(deftest a-test
  (testing "library-version"
    (is (re-matches #"(?>0|[1-9][0-9]*)\.(?>0|[1-9][0-9]*)\.(?>0|[1-9][0-9]*)" (library-version))))
  (testing "not-random"
    (is (run-nr-test (working-random)))
    (is (not (run-nr-test (broken-random))))))
