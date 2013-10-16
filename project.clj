(defproject crypto.openpgp "0.1.0-SNAPSHOT"
  :description "A Bouncy Castle based OpenPGP library for Clojure"
  :url "https://github.com/MailTube/crypto.openpgp"
  :license {:name "Public Domain (Unlicense)"
            :url "http://unlicense.org"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.bouncycastle/bcpg-jdk15on "1.49"]
                 [org.apache.commons/commons-math3 "3.2"]]
  :profiles {:codox {:plugins [[codox "0.6.6"]]
                     :dependencies [[codox-md "0.2.0"]]
                     :codox {:writer codox-md.writer/write-docs
                             :src-dir-uri "https://github.com/MailTube/crypto.openpgp/blob/master/"
                             :src-linenum-anchor-prefix "L"}}}
  :main crypto.openpgp)
