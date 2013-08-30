(ns crypto.openpgp
  (:import
    [java.security SecureRandom]
    [org.bouncycastle.openpgp PGPUtil]))

(defn library-version [] "0.1.0")

(defn -main
  []
  (println (ns-name ((meta #'library-version) :ns)) (library-version)))
