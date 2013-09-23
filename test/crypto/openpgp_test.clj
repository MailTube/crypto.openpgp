; A Bouncy Castle based OpenPGP library for Clojure.
; https://github.com/MailTube/crypto.openpgp
; This is free and unencumbered software released into the public domain.
(ns crypto.openpgp-test
  (:use clojure.test crypto.openpgp)
  (:import 
    [java.util Random]
    [java.io 
     ByteArrayOutputStream ByteArrayInputStream
     OutputStreamWriter InputStreamReader]))

;-------------------------------------------------------------------------------

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

;-------------------------------------------------------------------------------

(defn- test-string [] 
  "It doesn’t matter what you create if you have no fun.")

(defn- test-password [] [\p \a \s \s \w \o \r \d])

(defn- pbe-encryptor-test [string password]
  (let [os (new ByteArrayOutputStream),
        es (pbe-encryptor os password, :enarmor true),
        ew (new OutputStreamWriter es "UTF-8")]
    (spit ew string)
    (.toByteArray os)))

(defn- pbe-decryptor-test [buffer password]
  (let [is (new ByteArrayInputStream buffer),
        ds (pbe-decryptor is password),
        dr (new InputStreamReader ds "UTF-8")]
    (slurp dr)))

(deftest c-test
  (testing "pbe-encryptor/pbe-decryptor"
    (let [buffer (pbe-encryptor-test (test-string) (test-password)),
          string (pbe-decryptor-test buffer (test-password))]
      (is (= string (test-string))))))

;-------------------------------------------------------------------------------
