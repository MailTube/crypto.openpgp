; A Bouncy Castle based OpenPGP library for Clojure.
; https://github.com/MailTube/crypto.openpgp
; This is free and unencumbered software released into the public domain.
(ns crypto.openpgp
  (:import
    [java.util Date]
    [java.io FilterOutputStream FilterInputStream]
    [java.security SecureRandom]
    [org.apache.commons.math3.distribution BinomialDistribution]
    [org.apache.commons.math3.stat.inference ChiSquareTest]
    [org.apache.commons.math3.util FastMath]
    [org.bouncycastle.crypto.params 
     RSAKeyGenerationParameters DSAKeyGenerationParameters 
     ElGamalKeyGenerationParameters DSAParameterGenerationParameters]
    [org.bouncycastle.crypto.generators
     RSAKeyPairGenerator DSAParametersGenerator DSAKeyPairGenerator
     ElGamalParametersGenerator ElGamalKeyPairGenerator]
    [org.bouncycastle.crypto.digests SHA256Digest]
    [org.bouncycastle.openpgp
     PGPLiteralDataGenerator PGPCompressedDataGenerator PGPKeyPair
     PGPEncryptedDataGenerator PGPLiteralData PGPCompressedData 
     PGPPBEEncryptedData PGPObjectFactory PGPMarker PGPUtil
     PGPEncryptedDataList PGPKeyRingGenerator PGPSignature 
     PGPSignatureSubpacketGenerator PGPSignatureGenerator
     PGPSecretKey PGPSecretKeyRing PGPPublicKey PGPPublicKeyRing]
    [org.bouncycastle.openpgp.operator.bc 
     BcPGPDataEncryptorBuilder BcPBEKeyEncryptionMethodGenerator
     BcPBEDataDecryptorFactory BcPGPDigestCalculatorProvider
     BcPGPKeyPair BcPBESecretKeyEncryptorBuilder BcPGPContentSignerBuilder
     BcPBESecretKeyDecryptorBuilder BcKeyFingerprintCalculator]
    [org.bouncycastle.bcpg 
     CompressionAlgorithmTags SymmetricKeyAlgorithmTags
     ArmoredOutputStream PublicKeyAlgorithmTags HashAlgorithmTags
     RSAPublicBCPGKey PublicKeyPacket]
    [org.bouncycastle.bcpg.sig 
     KeyFlags Features RevocationReasonTags]))

;-------------------------------------------------------------------------------

(defn library-version [] "0.1.0")

(defmacro check
  ([x]
    `(when-not ~x
       (throw (new AssertionError (str "Check failed: " (pr-str '~x))))))
  ([x message]
    `(when-not ~x
       (throw (new AssertionError (str "Check failed: " ~message "\n" (pr-str '~x)))))))

;-------------------------------------------------------------------------------

(defn- nr-bitcount [input]
  (let [num (biginteger input)]
    (check (>= (.signum num) 0)) 
    (.bitCount num)))

(defn- nr-expected [n runs]
  (let [d (new org.apache.commons.math3.distribution.BinomialDistribution n 0.5)]
    (vec (map #(* runs (.probability d %)) (range (inc n))))))

(defn- nr-observed [input bitsize runs]
  (loop [v (transient (vec (repeat (inc bitsize) 0))), r0 (biginteger (input)), i 0] 
    (if (< i runs)
      (let [r1 (biginteger (input)), bc (nr-bitcount (.xor r0 r1))]
        (recur (assoc! v bc (inc (v bc))) r1 (inc i)))
      (persistent! v))))

(defn- nr-run [input bitsize runs alpha]
  (let 
    [observed (nr-observed input bitsize runs), 
     expected (nr-expected bitsize runs),
     t (new org.apache.commons.math3.stat.inference.ChiSquareTest)]
    (.chiSquareTest t (double-array expected) (long-array observed) alpha)))

; A rough and very simple test to estimate whether a random number generator is compromised. Implemented as a SAC-test (Castro, Sierra, Seznec, Izquierdo, Ribagorda, 2005). Parameters: 'random' is an object of type java.util.Random; 'bitsize' is the size in bits of every number to be generated by 'random' for evaluation; the total amount of generated numbers will be 'runs'+1; 'alpha' is a threshold for p-value. The function calculates p-value and returns true when p-value < 'alpha'.
(defn not-random [random bitsize runs alpha]
  (nr-run #(new java.math.BigInteger bitsize random) bitsize runs alpha))

;-------------------------------------------------------------------------------

(defn- gp-b62 [d]
  ([\0 \1 \2 \3 \4 \5 \6 \7 \8 \9 
    \a \b \c \d \e \f \g \h \i \j \k \l \m \n \o \p \q \r \s \t \u \v \w \x \y \z 
    \A \B \C \D \E \F \G \H \I \J \K \L \M \N \O \P \Q \R \S \T \U \V \W \X \Y \Z]
    d))

; Generates a secure password and returns it in the form of a vector of random Character's. Parameter 'entropy' specifies the password desired strength in bits. Optional named parameters: 'random' is an object of type java.util.Random, defaults to a new java.security.SecureRandom; 'radix' is the size of a password alphabet, defaults to 62; 'mapping' is a function that maps a number into a corresponding alphabet's Character, default function maps into Base62.
(defn gen-password [entropy & 
                    {:keys [random radix mapping]
                     :or {random (new java.security.SecureRandom),
                          radix 62, mapping gp-b62}}]
  (let [num (new BigInteger entropy random),
        len (FastMath/round 
              (FastMath/ceil (* entropy (FastMath/log radix 2.0)))),
        gen (fn [[q _]] (.divideAndRemainder q (biginteger radix)))]
    (shuffle 
      (map mapping (take len (rest (map second (iterate gen [num 0]))))))))

;-------------------------------------------------------------------------------

(defn default-partial [] 1048576)

(definterface ^:private ChainedClose)

(defn- chained-close [stream chained]
  (proxy [FilterOutputStream ChainedClose] [stream]
    (close [] 
      (.close stream) 
      (when (instance? ChainedClose chained)
        (.close chained)))))

(defn- skat-from-kw [kw]
  (case kw
    :TRIPLE-DES SymmetricKeyAlgorithmTags/TRIPLE_DES,
    :CAST5 SymmetricKeyAlgorithmTags/CAST5, 
    :BLOWFISH SymmetricKeyAlgorithmTags/BLOWFISH, 
    :AES-128 SymmetricKeyAlgorithmTags/AES_128,
    :AES-192 SymmetricKeyAlgorithmTags/AES_192, 
    :AES-256 SymmetricKeyAlgorithmTags/AES_256,
    :TWOFISH SymmetricKeyAlgorithmTags/TWOFISH,
    nil SymmetricKeyAlgorithmTags/NULL))

(defn- write-armored [os]
  (let [aos (new ArmoredOutputStream os)]
    (chained-close aos os)))

(defn- write-pbe-encrypted [os pw & 
                           {:keys [partial cipher integrity]
                            :or {partial (default-partial),
                                 cipher :AES-256,
                                 integrity true}}]
  (check (sequential? pw))
  (let [deb (new BcPGPDataEncryptorBuilder (skat-from-kw cipher))]
    (.setWithIntegrityPacket deb integrity)
    (let [edg (new PGPEncryptedDataGenerator deb),
          emg (new BcPBEKeyEncryptionMethodGenerator (char-array pw)),
          buffer (byte-array partial)]
      (.addMethod edg emg)
      (chained-close (.open edg os buffer) os))))

(defn- write-compressed [os &
                        {:keys [partial]
                         :or {partial (default-partial)}}]
  (let [cdg (new PGPCompressedDataGenerator CompressionAlgorithmTags/ZIP),
        buffer (byte-array partial)]
    (chained-close (.open cdg os buffer) os)))

(defn- write-literal [os &
                     {:keys [partial]
                      :or {partial (default-partial)}}]
  (let [ldg (new PGPLiteralDataGenerator),
        date (new Date 0),
        buffer (byte-array partial)]
    (chained-close (.open ldg os PGPLiteralData/BINARY "" date buffer) os)))

; Creates a password based encryptor. Parameters: 'output' is a java.io.OutputStream object that will receive ciphertext; 'password' is a sequential collection of Character's. Optional named parameters: 'enarmor' specifies whether to produce armored textual ciphertext, defaults to false; if 'compress' is false then no compression of plaintext will be done before encryption, default is to compress data; encryption will be performed with a 'cipher' algorithm, defaults to :AES-256; if 'integrity' is false then integrity packet that protects data from modification will not be written, default is to write this packet; 'partial' specifies the size in bytes of partial data packets to use during plaintext processing, compression and encryption phases, defaults to 1Mb. The function returns a java.io.OutputStream object for the caller application to write plaintext into. The returned stream must be closed if and only if all desired plaintext data was written into it successfully. Closing the returned stream does not close 'output'.
(defn pbe-encryptor [output password &
                     {:keys [enarmor compress] 
                      :or {enarmor false,
                           compress true} 
                      :as conf}]
  (let [forward (flatten (seq conf)),
        arm (if enarmor (write-armored output) output), 
        enc (apply (partial write-pbe-encrypted arm password) forward),
        com (if compress (apply (partial write-compressed enc) forward) enc),
        lit (apply (partial write-literal com) forward)]
    lit))

;-------------------------------------------------------------------------------

(defn- dearmor [is]
  (PGPUtil/getDecoderStream is))

(defn- object-factory [is]
  (let [of (new PGPObjectFactory is)]
    (filter #(not (instance? PGPMarker %)) (repeatedly #(.nextObject of)))))

(defn- parse-pbe-encrypted [of pw]
  (check (sequential? pw))
  (let [edl (cast PGPEncryptedDataList (first of)),
        ed (first (filter 
                    (partial instance? PGPPBEEncryptedData) 
                    (iterator-seq (.getEncryptedDataObjects edl))))]
    (check ed)
    (let [ddf (new BcPBEDataDecryptorFactory (char-array pw) 
                (new BcPGPDigestCalculatorProvider))]
      [(object-factory (.getDataStream ed ddf)) ed])))

(defn- parse-compressed [of]
  (let [ld (first of)]
    (if (not (instance? PGPCompressedData ld))
      of (object-factory (.getDataStream ld)))))

(defn- parse-literal [of]
  (let [ld (first (filter #(instance? PGPLiteralData %) of))]
    (check ld)
    (.getInputStream ld)))

; Creates a password based decryptor. Parameters: 'input' is a java.io.InputStream object that will be used as a source of ciphertext; 'password' is a sequential collection of Character's. The function returns a java.io.InputStream object for the caller application to read plaintext from. Closing the returned stream possibly drains all unread data, performs an integrity check and does not close 'input'.
(defn pbe-decryptor [input password]
  (let [[of ed] (parse-pbe-encrypted
                  (object-factory (dearmor input)) password),
        stream (parse-literal (parse-compressed of))]
    (proxy [FilterInputStream] [stream]
      (close []
        (check (.verify ed))))))

;-------------------------------------------------------------------------------

; PublicKeyring is a public portion of someone full keyring. Public keyrings are to be published in order to be accessible to anyone the full keyring owner wishes to securely communicate with.
(defrecord ^:private PublicKeyring [public])

; SecretKeyring is someone full keyring. Secret keyrings are to be protected in order to be accessible to the keyring owner only; communications of the keyring owner will not be secure otherwise.
(defrecord ^:private SecretKeyring [secret])

(defprotocol ^:private Keyring
  (^:private keyring-get [keyring])
  (^:private keyring-get-public [keyring])
  (^:private keyring-get-public-key [keyring])
  (^:private keyring-put-public-key [keyring key]))

(defn- prime-certainty [] 80)

(defn- rrt-from-kw [kw]
  (case kw
    :KEY-COMPROMISED RevocationReasonTags/KEY_COMPROMISED,
    :KEY-RETIRED RevocationReasonTags/KEY_RETIRED,
    :KEY-SUPERSEDED RevocationReasonTags/KEY_SUPERSEDED,
    :NO-REASON RevocationReasonTags/NO_REASON,
    :USER-NO-LONGER-VALID RevocationReasonTags/USER_NO_LONGER_VALID))

(defn- csl-from-kw [kw]
  (case kw
    :DEFAULT-CERTIFICATION PGPSignature/DEFAULT_CERTIFICATION,
    :NO-CERTIFICATION PGPSignature/NO_CERTIFICATION,
    :CASUAL-CERTIFICATION PGPSignature/CASUAL_CERTIFICATION,
    :POSITIVE-CERTIFICATION PGPSignature/POSITIVE_CERTIFICATION))

(defn- gen-rsa-kp [random strength]
  (let [kgp (new RSAKeyGenerationParameters 
              (biginteger 65537) random strength (prime-certainty)),
        kpg (new RSAKeyPairGenerator)]
    (.init kpg kgp) 
    (.generateKeyPair kpg)))

(defn- gen-dsa-kp [random l-strength]
  (let [n-strength (if (> l-strength 1024) 256 160),
        pgp (new DSAParameterGenerationParameters 
              l-strength n-strength (prime-certainty) random 
              DSAParameterGenerationParameters/DIGITAL_SIGNATURE_USAGE),
        pg (new DSAParametersGenerator (new SHA256Digest))]
    (.init pg pgp)
    (let [p (.generateParameters pg),
          kgp (new DSAKeyGenerationParameters random p),
          kpg (new DSAKeyPairGenerator)]
      (.init kpg kgp) 
      (.generateKeyPair kpg))))

(defn- gen-elg-kp [random strength]
  (let [pg (new ElGamalParametersGenerator)]
    (.init pg strength (prime-certainty) random)
    (let [p (.generateParameters pg),
          kgp (new ElGamalKeyGenerationParameters random p),
          kpg (new ElGamalKeyPairGenerator)]
      (.init kpg kgp) 
      (.generateKeyPair kpg))))

(defn- gen-keypair [algorithm random strength date]
  (case algorithm 
    :DSA 
    (new BcPGPKeyPair PublicKeyAlgorithmTags/DSA 
      (gen-dsa-kp random strength) date),
    :RSA-SIGN 
    (new BcPGPKeyPair PublicKeyAlgorithmTags/RSA_SIGN 
      (gen-rsa-kp random strength) date),
    :RSA-ENCRYPT
    (new BcPGPKeyPair PublicKeyAlgorithmTags/RSA_ENCRYPT 
      (gen-rsa-kp random strength) date),
    :ELGAMAL-ENCRYPT
    (new BcPGPKeyPair PublicKeyAlgorithmTags/ELGAMAL_ENCRYPT 
      (gen-elg-kp random strength) date)))

(defn- gen-ssv [date issuer & {:keys [expire flags mdc revocation revoker 
                                      embedded]}]
  (let [cr true, ssg (new PGPSignatureSubpacketGenerator)]
    (.setRevocable ssg cr false)
    (.setSignatureCreationTime ssg cr date)
    (.setIssuerKeyID ssg cr issuer)
    (when expire (when (> expire 0)
      (.setKeyExpirationTime ssg cr expire)))
    (when flags 
      (.setKeyFlags ssg cr 
        (case flags
          :encrypt (bit-or KeyFlags/ENCRYPT_COMMS KeyFlags/ENCRYPT_STORAGE),
          :sign-certify (bit-or KeyFlags/SIGN_DATA KeyFlags/CERTIFY_OTHER),
          :sign KeyFlags/SIGN_DATA)))
    (when mdc
      (.setFeature ssg cr Features/FEATURE_MODIFICATION_DETECTION))
    (when revocation
      (.setRevocationReason ssg cr (rrt-from-kw revocation) ""))
    (when revoker
      (.setRevocationKey ssg cr (first revoker) (second revoker)))
    (when embedded
      (.setEmbeddedSignature ssg cr embedded))
    (.generate ssg)))

(defn- gen-ssv-std [date issuer expire]
  (gen-ssv date issuer, :expire expire, :mdc true, :flags :sign-certify))

(defn- key-collide? [key1 key2]
  (= (.getKeyID key1) (.getKeyID key2)))

(defn- key-pair? [public secret]
  (and (instance? PGPPublicKey public) (instance? PGPSecretKey secret)
    (key-collide? public secret)))

(defn- key-signing? [key]
  (and (instance? PGPSecretKey key) (.isSigningKey key)))

(defn- key-encryption? [key]
  (and (instance? PGPPublicKey key) (.isEncryptionKey key)))

(defn- key-master? [& keys]
  (empty? (filter false? (map #(.isMasterKey %) keys))))

(defn- keyring-public [secret]
  (let [stub (new PGPPublicKey ; PGPPublicKeyRing(List) is not accessible.
               (new PublicKeyPacket PublicKeyAlgorithmTags/RSA_SIGN
                 (new Date 0) (new RSAPublicBCPGKey 
                                (biginteger 3) (biginteger 3))) 
               (new BcKeyFingerprintCalculator)),
        init (new PGPPublicKeyRing (.getEncoded stub) 
               (new BcKeyFingerprintCalculator)),
        ring (PGPPublicKeyRing/removePublicKey init (.getPublicKey init))]
    (check (empty? (iterator-seq (.getPublicKeys ring))))
    (let [keys (iterator-seq (.getPublicKeys secret))]
      (reduce #(PGPPublicKeyRing/insertPublicKey %1 %2) ring keys))))

; Generates a minimal keyring suitable for signing and encryption. Parameters: 'userid' is a String identifying the keyring owner; 'password' is a sequential collection of Character's for a private keys PBE protection. Optional named parameters: 'random' is an object of type java.security.SecureRandom, defaults to a new instance; 'date' is an object of type java.util.Date representing a keyring creation timestamp, defaults to the current time; both 'master' and 'encryption' parameters are two-element collections each specifying the desired algorithm (the first element) and strength (the second element) of master and encryption keypairs respectively, defaults are [:DSA 2048] for master and [:RSA-ENCRYPT 2048] for encryption, 'encryption' may be nil for no encryption keypair generation; the private keys PBE protection will be performed with a 'cipher' algorithm, defaults to :AES-256; the keyring will become obsoleted in 'expire' seconds after the 'date' timestamp, defaults to 0 which means no expiration; 'level' is a keyword defining a type of a self-signed certification that will be included in the keyring, defaults to :POSITIVE-CERTIFICATION. The function returns a new keyring in the form of a SecretKeyring object.
(defn gen-keyring [userid password & 
                   {:keys [random date master encryption cipher expire level] 
                    :or {random (new java.security.SecureRandom),
                         date (new Date),
                         master [:DSA 2048],
                         encryption [:RSA-ENCRYPT 2048],
                         cipher :AES-256,
                         expire 0,
                         level :POSITIVE-CERTIFICATION}}]
  (check (sequential? password))
  (let [skp (gen-keypair (first master) random (second master) date),
        skeb (new BcPBESecretKeyEncryptorBuilder (skat-from-kw cipher) 
               (.get (new BcPGPDigestCalculatorProvider) 
                 HashAlgorithmTags/SHA256))]
    (.setSecureRandom skeb random)
    (let [ske (.build skeb (char-array password)),
          dc (.get (new BcPGPDigestCalculatorProvider) 
               HashAlgorithmTags/SHA1),
          csb (new BcPGPContentSignerBuilder 
                (.getAlgorithm (.getPublicKey skp)) HashAlgorithmTags/SHA256)]
      (.setSecureRandom csb random)
      (let [sss (gen-ssv-std date (.getKeyID skp) expire),
            krg (new PGPKeyRingGenerator (csl-from-kw level)
                  skp userid dc sss nil csb ske)]
        (when encryption
          (let [ekp (gen-keypair 
                      (first encryption) random (second encryption) date),
                ess (gen-ssv date (.getKeyID skp), 
                      :expire expire, :flags :encrypt)]
            (.addSubKey krg ekp ess nil)))
        (->SecretKeyring (PGPSecretKeyRing/replacePublicKeys 
                           (.generateSecretKeyRing krg) 
                           (.generatePublicKeyRing krg)))))))

; Adds a new certification to a keyring. A certification binds keys of a keyring to a particular user identifier. A keyring generated by the 'gen-keyring' API already contains a self-signed certificate. Parameters: 'keyring' is a SecretKeyring or PublicKeyring object containing the keyring to be certified; 'userid' is a user String identifier that will be bound to the 'keyring' by the newly generated certificate; 'signer' is a SecretKeyring object that will be used as a certificate issuer (self-signing is allowed, i.e. 'keyring' and 'signer' could belong to the same keyring); 'password' is a sequential collection of Character's for the purpose of private signing key extraction from the 'signer'. Optional named parameters: 'random' is an object of type java.security.SecureRandom, defaults to a new instance; 'date' is an object of type java.util.Date representing a certification creation timestamp, defaults to the current time; when self-signing, the certification will obsolete the keyring in 'expire' seconds after the 'date' timestamp, defaults to 0 which means no expiration; 'level' is a keyword defining a type of the certificate, defaults to :POSITIVE-CERTIFICATION. The function returns a 'keyring' clone with the certification added.
(defn keyring-certify [keyring userid signer password &
                       {:keys [random date expire level] 
                        :or {random (new java.security.SecureRandom),
                             date (new Date),
                             expire 0,
                             level :POSITIVE-CERTIFICATION}}]
  (check (sequential? password))
  (let [pubk (keyring-get-public-key keyring),
        sigk (.getSecretKey (:secret signer)),
        selfsigning (key-collide? sigk pubk)]
    (check (and (key-master? pubk sigk) (key-signing? sigk)))
    (let [csb (new BcPGPContentSignerBuilder
                (.getAlgorithm (.getPublicKey sigk)) 
                HashAlgorithmTags/SHA256)]
      (.setSecureRandom csb random)
      (let [sg (new PGPSignatureGenerator csb),
            skdb (new BcPBESecretKeyDecryptorBuilder 
                   (new BcPGPDigestCalculatorProvider)),
            ssv (if selfsigning 
                  (gen-ssv-std date (.getKeyID sigk) expire) 
                  (gen-ssv date (.getKeyID sigk)))]
        (.init sg (csl-from-kw level)
          (.extractPrivateKey sigk (.build skdb (char-array password))))
        (.setHashedSubpackets sg ssv)
        (let [sig (.generateCertification sg userid pubk),
              cpubk (PGPPublicKey/addCertification pubk userid sig)]
          (keyring-put-public-key keyring cpubk))))))

; Generates a new keypair suitable for signing or encryption and adds it to a keyring. Parameters: 'keyring' is a SecretKeyring object containing the keyring to add a keypair to; 'password' is a sequential collection of Character's for the purpose of private master key extraction from the 'keyring'; 'usage' is a keyword selecting whether to generate a signing or an encryption keypair. Optional named parameters: 'random' is an object of type java.security.SecureRandom, defaults to a new instance; 'date' is an object of type java.util.Date representing a keypair creation timestamp, defaults to the current time; 'subkeypair' parameter is a two-element collection specifying the desired algorithm (the first element) and strength (the second element) of a keypair, defaults are [:RSA-SIGN 2048] for signing and [:RSA-ENCRYPT 2048] for encryption; 'subpassword' is a sequential collection of Character's for the keypair private key PBE protection, defaults to 'password'; the keypair private key PBE protection will be performed with a 'cipher' algorithm, defaults to :AES-256; the keypair will become obsoleted in 'expire' seconds after the 'date' timestamp, defaults to 0 which means no expiration. The function returns a 'keyring' clone with a newly generated keypair added. Possible values for 'usage': :sign, :encrypt.
(defn keyring-add-subkeypair [keyring password usage & 
                              {:keys [random date subkeypair 
                                      subpassword cipher expire] 
                               :or {random (new java.security.SecureRandom),
                                    date (new Date),
                                    subkeypair (case usage 
                                                 :sign [:RSA-SIGN 2048],
                                                 :encrypt [:RSA-ENCRYPT 2048])
                                    subpassword password, 
                                    cipher :AES-256,
                                    expire 0}}]
  (check (and (sequential? password) (sequential? subpassword)))
  (let [mpubk (.getPublicKey (:secret keyring)), 
        mseck (.getSecretKey (:secret keyring)),
        kp (gen-keypair (first subkeypair) random (second subkeypair) date),
        skdb (new BcPBESecretKeyDecryptorBuilder 
               (new BcPGPDigestCalculatorProvider)),
        skeb (new BcPBESecretKeyEncryptorBuilder (skat-from-kw cipher) 
               (.get (new BcPGPDigestCalculatorProvider) 
                 HashAlgorithmTags/SHA256))]
    (check (and (key-master? mpubk) (key-signing? mseck)))
    (.setSecureRandom skeb random)
    (let [skd (.build skdb (char-array password)),
          ske (.build skeb (char-array subpassword)),
          mprik (.extractPrivateKey mseck skd),
          dc (.get (new BcPGPDigestCalculatorProvider) 
               HashAlgorithmTags/SHA1),
          csb (new BcPGPContentSignerBuilder 
                (.getAlgorithm mpubk) HashAlgorithmTags/SHA256)]
      (.setSecureRandom csb random)
      (let [es (when (= usage :sign)
                 (let [escsb (new BcPGPContentSignerBuilder 
                               (.getAlgorithm (.getPublicKey kp))
                               HashAlgorithmTags/SHA256), 
                       sg (new PGPSignatureGenerator escsb)]
                   (.init sg PGPSignature/PRIMARYKEY_BINDING 
                     (.getPrivateKey kp))
                   (.generateCertification sg mpubk (.getPublicKey kp))))
            ss (gen-ssv date (.getKeyID mpubk), 
                 :expire expire, :flags usage, :embedded es),
            stub (new PGPKeyRingGenerator ; PGPSecretKey(PGPPrivateKey, PGPPublicKey, PGPDigestCalculator, boolean, PBESecretKeyEncryptor) is not accessible.
                   PGPSignature/NO_CERTIFICATION
                   (new PGPKeyPair mpubk mprik) "(stub)" dc nil nil csb ske)]
        (.addSubKey stub kp ss nil)
        (let [tkr (PGPSecretKeyRing/replacePublicKeys 
                    (.generateSecretKeyRing stub) 
                    (.generatePublicKeyRing stub)),
              key (second (iterator-seq (.getSecretKeys tkr)))]
          (->SecretKeyring 
            (PGPSecretKeyRing/insertSecretKey (:secret keyring) key)))))))

; Adds a new revocation certificate to a keyring. A revoked keyring should no longer be used for signing or encryption. Parameters: 'keyring' is a SecretKeyring or PublicKeyring object containing the keyring to be revoked; 'revoker' is a SecretKeyring object that will be used as a revocation issuer (authorized revoke is allowed, i.e. 'keyring' and 'revoker' could belong to different keyrings); 'password' is a sequential collection of Character's for the purpose of private signing key extraction from the 'revoker'. Optional named parameters: 'random' is an object of type java.security.SecureRandom, defaults to a new instance; 'date' is an object of type java.util.Date representing a revocation creation timestamp, defaults to the current time; 'reason' is a keyword specifying the revocation reason tag to be included in the generated certificate, defaults to nil which means no tagging. The function returns a 'keyring' clone with the revocation added.
(defn keyring-revoke [keyring revoker password &
                      {:keys [random date reason] 
                       :or {random (new java.security.SecureRandom),
                            date (new Date),
                            reason nil}}]
  (check (sequential? password))
  (let [pubk (keyring-get-public-key keyring),
        revk (.getSecretKey (:secret revoker))]
    (check (and (key-master? pubk revk) (key-signing? revk)))
    (let [csb (new BcPGPContentSignerBuilder
                (.getAlgorithm (.getPublicKey revk)) 
                HashAlgorithmTags/SHA256)]
      (.setSecureRandom csb random)
      (let [sg (new PGPSignatureGenerator csb),
            skdb (new BcPBESecretKeyDecryptorBuilder 
                   (new BcPGPDigestCalculatorProvider)),
            ssv (gen-ssv date (.getKeyID revk), :revocation reason)]
        (.init sg PGPSignature/KEY_REVOCATION
          (.extractPrivateKey revk (.build skdb (char-array password))))
        (.setHashedSubpackets sg ssv)
        (let [sig (.generateCertification sg pubk),
              cpubk (PGPPublicKey/addCertification pubk sig)]
          (keyring-put-public-key keyring cpubk))))))

; Adds a revoker authorization to a keyring. An authorization is created by a keyring owner in order to allow a revoker to revoke a keyring without being its owner. Parameters: 'keyring' is a SecretKeyring object containing the keyring to add an authorization to; 'revoker' is a SecretKeyring or PublicKeyring object which keyring will be authorized as a valid 'keyring' revocation issuer; 'password' is a sequential collection of Character's for the purpose of private signing key extraction from the 'keyring'. Optional named parameters: 'random' is an object of type java.security.SecureRandom, defaults to a new instance; 'date' is an object of type java.util.Date representing an authorization creation timestamp, defaults to the current time; The function returns a 'keyring' clone with the authorization added.
(defn keyring-add-revoker [keyring password revoker &
                           {:keys [random date] 
                            :or {random (new java.security.SecureRandom),
                                 date (new Date)}}]
  (check (sequential? password))
  (let [pubk (.getPublicKey (:secret keyring)),
        seck (.getSecretKey (:secret keyring)),
        revk (keyring-get-public-key revoker)]
    (check (and (key-master? pubk revk) (key-signing? seck)))
    (let [csb (new BcPGPContentSignerBuilder
                (.getAlgorithm pubk) HashAlgorithmTags/SHA256)]
      (.setSecureRandom csb random)
      (let [sg (new PGPSignatureGenerator csb),
            skdb (new BcPBESecretKeyDecryptorBuilder 
                   (new BcPGPDigestCalculatorProvider)),
            ssv (gen-ssv date (.getKeyID pubk), 
                  :revoker [(.getAlgorithm revk) (.getFingerprint revk)])]
        (.init sg PGPSignature/DIRECT_KEY
          (.extractPrivateKey seck (.build skdb (char-array password))))
        (.setHashedSubpackets sg ssv)
        (let [sig (.generateCertification sg pubk),
              cpubk (PGPPublicKey/addCertification pubk sig)]
          (keyring-put-public-key keyring cpubk))))))

; Changes keyring passwords that protect private keys of a keyring. Parameters: 'keyring' is a SecretKeyring object containing the keyring which passwords are going be changed; 'old-password' and 'new-password' are sequential collections of Character's representing old and new passwords respectively. Optional named parameters: 'random' is an object of type java.security.SecureRandom, defaults to a new instance; the renewed private keys PBE protection will be performed with a 'cipher' algorithm, defaults to :AES-256; 'scope' is a keyword that specifies what secret keys to update, defaults to :all. The function returns an updated keyring in the form of a new SecretKeyring object. Possible values for 'scope': :all, :master, :subkeys.   
(defn keyring-password [keyring old-password new-password & 
                        {:keys [random cipher scope] 
                         :or {random (new java.security.SecureRandom),
                              cipher :AES-256,
                              scope :all}}]
  (check (and (sequential? old-password) (sequential? new-password)))
  (let [bckr (:secret keyring),
        mk (.getSecretKey bckr),
        skdb (new BcPBESecretKeyDecryptorBuilder 
               (new BcPGPDigestCalculatorProvider)),
        skeb (new BcPBESecretKeyEncryptorBuilder (skat-from-kw cipher)
               (.get (new BcPGPDigestCalculatorProvider) 
                 HashAlgorithmTags/SHA256))]
    (check (key-master? mk))
    (.setSecureRandom skeb random)
    (let [skd (.build skdb (char-array old-password)),
          ske (.build skeb (char-array new-password))]
      (case scope
        :all
        (->SecretKeyring (PGPSecretKeyRing/copyWithNewPassword bckr skd ske)),
        :master
        (let [umk (PGPSecretKey/copyWithNewPassword mk skd ske)]
          (->SecretKeyring (PGPSecretKeyRing/insertSecretKey bckr umk))),
        :subkeys
        (let [tkr (PGPSecretKeyRing/removeSecretKey bckr mk),
              ukr (PGPSecretKeyRing/copyWithNewPassword tkr skd ske)]
          (->SecretKeyring (PGPSecretKeyRing/insertSecretKey ukr mk)))))))

; Extracts public keys from a keyring. Parameter 'keyring' is an object of SecretKeyring or PublicKeyring type. The function returns keys extracted from the 'keyring' in the form of a new PublicKeyring object.
(defn keyring-publish [keyring]
  (keyring-get-public keyring))

; Serializes a keyring. Parameters: 'keyring' is a SecretKeyring or PublicKeyring object containing the keys to be serialized; 'output' is a java.io.OutputStream object that will receive encoded data. Optional named parameter 'enarmor' specifies whether to produce armored textual encoding, defaults to false.
(defn keyring-save [keyring output & 
                    {:keys [enarmor] 
                     :or {enarmor false}}]
  (let [os (if enarmor (new ArmoredOutputStream output) output)]
    (.encode (keyring-get keyring) os)
    (when enarmor
      (.close os))))

; Tests whether a keyring contains a revocation of its master keypair. Parameter 'keyring' is an object of SecretKeyring or PublicKeyring type. The function returns true if the 'keyring' is revoked or false otherwise.
(defn keyring-revoked? [keyring]
  (.isRevoked (keyring-get-public-key keyring)))

(extend-protocol Keyring PublicKeyring

  (keyring-get [keyring]
    (:public keyring))
  
  (keyring-get-public [keyring]
    keyring)

  (keyring-get-public-key [keyring]
    (.getPublicKey (:public keyring)))
  
  (keyring-put-public-key [keyring key]
    (->PublicKeyring (PGPPublicKeyRing/insertPublicKey 
                       (:public keyring) key)))

  )

(extend-protocol Keyring SecretKeyring
  
  (keyring-get [keyring]
    (:secret keyring))

  (keyring-get-public [keyring]
    (->PublicKeyring (keyring-public (:secret keyring))))

  (keyring-get-public-key [keyring]
    (.getPublicKey (:secret keyring)))
  
  (keyring-put-public-key [keyring key]
    (let [sk (.getSecretKey (:secret keyring)),
          usk (PGPSecretKey/replacePublicKey sk key)]
      (->SecretKeyring (PGPSecretKeyRing/insertSecretKey 
                         (:secret keyring) usk))))
    
  )

;-------------------------------------------------------------------------------

(defn -main []
  (println (ns-name ((meta #'library-version) :ns)) (library-version)))

;-------------------------------------------------------------------------------
