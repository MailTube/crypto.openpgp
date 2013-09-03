(defproject crypto.openpgp "0.1.0-SNAPSHOT"
  :description "A Bouncy Castle based OpenPGP library for Clojure"
  :url "https://github.com/MailTube/crypto.openpgp"
  :license {:name "Public Domain (Unlicense)"
            :url "http://unlicense.org"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.bouncycastle/bcpg-jdk15on "1.49"]
                 [org.apache.commons/commons-math3 "3.2"]]
  :main crypto.openpgp)
