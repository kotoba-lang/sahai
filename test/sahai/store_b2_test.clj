(ns sahai.store-b2-test
  "Contract tests for the B2 JSON API paths in `sahai.store`.

   The b2_* routes were never covered by CI — the 2026-08-29 data.json
   retirement shipped a latent ArityException in B2 body parsing (fixed in
   PR #2) that no test detected, because every existing test runs the
   disk/memory/composite stores only. These tests close that gap with a
   genuinely stateful fake B2 backend (same fake-server technique as
   kotoba-lang/amu's storage_transport_test.clj):

     - b2_authorize_account  GET  (Basic auth; returns apiUrl/downloadUrl)
     - b2_list_buckets       POST (token auth; bucket-name lookup)
     - b2_get_upload_url     POST (token auth; per-upload URL + token)
     - b2_upload_file        POST (upload token; X-Bz-File-Name +
                                  X-Bz-Content-Sha1 enforced against the
                                  actual request body)
     - download-by-name      GET   (token auth; files saved by uploads)

   The fake refuses mismatched credentials, mismatched digests, and unknown
   buckets, so a green run *is* the contract: JSON bodies parse into
   keywordized maps (the PR #2 regression class), non-2xx maps to ex-info
   with :status, the SHA-1 header matches the uploaded bytes, and the lazy
   authorize→list→get-upload-url→upload chain happens exactly once per
   session.

   `b2-authorize!` is the only route whose host is not taken from the
   authorize response. store.clj keeps that endpoint in an atom (public B2
   host by default; B2_API_URL env override) so these tests rebind it to the
   fake — no production env and no live network involved. `b2-store` itself
   is driven with explicit :key-id/:app-key/:bucket/:prefix opts, which
   override any ambient B2_* env, so the suite is hermetic everywhere.

   No live-network integration test: B2 contract is pinned by this fake."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [json.core :as json]
            [sahai.store :as store])
  (:import (com.sun.net.httpserver HttpServer HttpHandler)
           (java.net InetSocketAddress URLDecoder)
           (java.util Base64)))

;; ── fake B2 constants (the credentials the fake accepts) ────────────────────

(def ^:private fake-key-id "test-key-id")
(def ^:private fake-app-key "test-app-key")
(def ^:private fake-bucket "test-bucket")
(def ^:private fake-bucket-id "bucket-123")
(def ^:private fake-account-id "test-account-id")
(def ^:private fake-auth-token "fake-auth-token-1")
(def ^:private fake-upload-token "fake-upload-token-1")

(def ^:private endpoint-var
  "The Var object holding store's endpoint atom. (deref endpoint-var) yields
   the atom itself, so tests reset! the production atom in place and restore
   it afterwards — the Var root is never replaced."
  #'store/b2-authorize-endpoint)

(def ^:private default-authorize-endpoint
  "Production default authorize URL, captured as a plain string before any
   test rebinds the endpoint atom."
  (deref (deref endpoint-var)))

(defn- b64 ^String [^String s]
  (.encodeToString (Base64/getEncoder) (.getBytes s "UTF-8")))

(defn- expected-basic-auth ^String []
  (str "Basic " (b64 (str fake-key-id ":" fake-app-key))))

(def ^:private expected-basic-auth-str
  "Precomputed once; calling the fn directly inside = would compare the fn
   object itself on the left, not its return value."
  (expected-basic-auth))

(def ^:dynamic *fake-b2*
  "Bound by with-fake-b2 / with-fake-b2-failing to the running fake
   ({:keys [state origin server]}). A dynamic var instead of a macro-bound
   symbol keeps every reference statically resolvable (clj-kondo clean).")

(defn- events []
  (:events @(:state *fake-b2*)))

(defn- events-of [op]
  (filterv #(= op (:op %)) (events)))

(defn- utf8-bytes ^bytes [^String s]
  (.getBytes s "UTF-8"))

(defn- sha1-hex [^bytes bs]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-1") bs)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

;; ── fake B2 server ───────────────────────────────────────────────────────────

(defn- respond! [exchange status ^String body]
  (let [bytes (utf8-bytes body)]
    (.sendResponseHeaders exchange status (count bytes))
    (doto (.getResponseBody exchange)
      (.write bytes)
      (.close))))

(defn- fake-handler [state origin ^com.sun.net.httpserver.HttpExchange exchange]
  (try
    ;; NOTE: HttpExchange#getPath is JDK 18+; getRequestURI().getPath() is the
    ;; portable equivalent (URI#getPath returns the decoded path) and works on
    ;; the JDK 21 CI and older local JDKs alike.
    (let [path (.. exchange getRequestURI getPath)
          method (.getRequestMethod exchange)
          headers (.getRequestHeaders exchange)
          header (fn [k] (.getFirst headers k))
          body-bytes (.readAllBytes (.getRequestBody exchange))
          body-str (String. body-bytes "UTF-8")
          fail (:fail @state)
          event (fn [m] (swap! state update :events conj m))
          respond (fn [status body] (respond! exchange status body))]
      (cond
        ;; ── b2_authorize_account: Basic auth → session tokens ────────────────
        (and (= "/b2api/v2/b2_authorize_account" path) (= "GET" method))
        (do (event {:op :authorize :authorization (header "Authorization")})
            (cond
              (not= (expected-basic-auth) (header "Authorization"))
              (respond 401 (json/encode {:status 401 :code "bad_auth_token"
                                         :message "wrong credentials"}))
              (get fail :authorize-status)
              (respond (get fail :authorize-status)
                       (json/encode {:status 500 :code "injected" :message "injected failure"}))
              (get fail :authorize-malformed-body?)
              (respond 200 "{")
              :else
              (respond 200 (json/encode {:accountId fake-account-id
                                         :authorizationToken fake-auth-token
                                         :apiUrl @origin
                                         :downloadUrl (str @origin "/download")}))))

        ;; ── b2_list_buckets: token auth, name → id, unknown name → empty ─────
        (and (= "/b2api/v2/b2_list_buckets" path) (= "POST" method))
        (let [req (walk/keywordize-keys (json/decode body-str))]
          (event {:op :list-buckets :authorization (header "Authorization")
                  :content-type (header "Content-Type") :request req})
          (cond
            (not= fake-auth-token (header "Authorization"))
            (respond 401 (json/encode {:status 401 :code "expired_auth_token"}))
            (or (get fail :list-empty?)
                (not= fake-account-id (:accountId req))
                (not= fake-bucket (:bucketName req)))
            (respond 200 (json/encode {:buckets []}))
            :else
            (respond 200 (json/encode {:buckets [{:bucketId fake-bucket-id
                                                  :bucketName (:bucketName req)
                                                  :bucketType "allPrivate"}]}))))

        ;; ── b2_get_upload_url: token auth, bucketId must match ──────────────
        (and (= "/b2api/v2/b2_get_upload_url" path) (= "POST" method))
        (let [req (walk/keywordize-keys (json/decode body-str))]
          (event {:op :get-upload-url :authorization (header "Authorization")
                  :request req})
          (cond
            (not= fake-auth-token (header "Authorization"))
            (respond 401 (json/encode {:status 401 :code "expired_auth_token"}))
            (get fail :get-upload-url-status)
            (respond (get fail :get-upload-url-status)
                     (json/encode {:status 500 :code "injected" :message "injected failure"}))
            (not= fake-bucket-id (:bucketId req))
            (respond 400 (json/encode {:status 400 :code "bad_bucket_id"}))
            :else
            (respond 200 (json/encode {:bucketId fake-bucket-id
                                       :uploadUrl (str @origin "/upload/u1")
                                       :authorizationToken fake-upload-token}))))

        ;; ── b2_upload_file: upload token + SHA-1 enforced against the body ──
        (and (= "/upload/u1" path) (= "POST" method))
        (let [file-name (some-> (header "X-Bz-File-Name")
                                (URLDecoder/decode "UTF-8"))
              sha (header "X-Bz-Content-Sha1")]
          (event {:op :upload :authorization (header "Authorization")
                  :file-name file-name :sha1 sha
                  :content-type (header "Content-Type")
                  :content-length (count body-bytes)})
          (cond
            (not= fake-upload-token (header "Authorization"))
            (respond 401 (json/encode {:status 401 :code "expired_auth_token"}))
            (get fail :upload-status)
            (respond (get fail :upload-status)
                     (json/encode {:status 500 :code "injected" :message "injected failure"}))
            (or (nil? sha) (not= (sha1-hex body-bytes) sha))
            (respond 400 (json/encode {:status 400 :code "bad_digest"
                                       :message "X-Bz-Content-Sha1 mismatch"}))
            :else
            (do (swap! state assoc-in [:files file-name] body-str)
                (respond 200 (json/encode {:fileId (str "4_" (Math/abs (hash file-name)))
                                           :fileName file-name
                                           :accountId fake-account-id
                                           :bucketId fake-bucket-id
                                           :contentLength (count body-bytes)
                                           :contentSha1 sha
                                           :contentType (header "Content-Type")})))))


        ;; ── download by name: token auth, files uploaded above ──────────────
        (and (.startsWith path "/download/file/") (= "GET" method))
        (let [prefix (str "/download/file/" fake-bucket "/")
              file-name (when (.startsWith path prefix)
                          (subs path (count prefix)))]
          (event {:op :download :authorization (header "Authorization")
                  :file-name file-name})
          (cond
            (not= fake-auth-token (header "Authorization"))
            (respond 401 (json/encode {:status 401 :code "expired_auth_token"}))
            (or (nil? file-name) (empty? file-name))
            (respond 404 (json/encode {:status 404 :code "not_found"}))
            :else
            (if-let [content (get (:files @state) file-name)]
              (respond 200 content)
              (respond 404 (json/encode {:status 404 :code "not_found"
                                         :message "no such file"})))))

        :else
        (respond 404 (json/encode {:status 404 :code "not_found" :path path}))))
    (catch Exception e
      (try (respond! exchange 500 (str "fake-b2 error: " (.getMessage e)))
           (catch Exception _ nil)))))

(defn- start-fake-b2!
  "Start the fake B2 server on 127.0.0.1:0, point sahai.store's authorize
   endpoint at it, and return {:keys [state origin server]}."
  [fail]
  (let [state (atom {:files {} :events [] :fail (or fail {})})
        origin (atom nil)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/" (reify HttpHandler
                                 (handle [_ ex] (fake-handler state origin ex))))
    (.setExecutor server nil)
    (.start server)
    (let [port (.getPort (.getAddress server))]
      (reset! origin (str "http://127.0.0.1:" port))
      (reset! @endpoint-var (str @origin "/b2api/v2/b2_authorize_account"))
      {:state state :origin @origin :server server})))

(defn- stop-fake-b2! [{:keys [server]}]
  (.stop server 0)
  (reset! @endpoint-var default-authorize-endpoint))

(defmacro with-fake-b2
  "Runs body with *fake-b2* bound to a fresh fake B2 server, always stopping
   it and restoring the real authorize endpoint afterwards."
  [& body]
  `(let [fake# (start-fake-b2! {})]
     (try
       (binding [*fake-b2* fake#] ~@body)
       (finally (stop-fake-b2! fake#)))))

(defmacro with-fake-b2-failing
  "Like with-fake-b2, but seeds the fake's injected-failure map."
  [fail & body]
  `(let [fake# (start-fake-b2! ~fail)]
     (try
       (binding [*fake-b2* fake#] ~@body)
       (finally (stop-fake-b2! fake#)))))

(defn- b2-creds []
  {:key-id fake-key-id :app-key fake-app-key
   :bucket fake-bucket :prefix "kototama-fleet/"})

(defn- b2-auth []
  (#'store/b2-authorize! (select-keys (b2-creds) [:key-id :app-key])))

;; ── pure unit: fail-closed credential decision ───────────────────────────────

(deftest b2-authorization-decision-fails-closed-on-blank-credentials
  (testing "blank key-id / app-key are rejected before any endpoint is touched"
    (let [{:b2-authorization/keys [allowed? violations]}
          (store/b2-authorization-decision {:key-id "" :app-key "k"})]
      (is (false? allowed?))
      (is (= [:key-id-required] violations)))
    (let [{:b2-authorization/keys [allowed? violations]}
          (store/b2-authorization-decision {:key-id "k" :app-key nil})]
      (is (false? allowed?))
      (is (= [:application-key-required] violations))))
  (testing "complete credentials are allowed and carry the configured endpoint"
    (with-fake-b2
      (let [{:b2-authorization/keys [allowed? violations endpoint]}
            (store/b2-authorization-decision {:key-id "k" :app-key "a"})]
        (is (true? allowed?))
        (is (empty? violations))
        (is (= (str (:origin *fake-b2*) "/b2api/v2/b2_authorize_account") endpoint))))))

;; ── JSON body contract (the PR #2 regression class) ──────────────────────────

(deftest b2-authorize-parses-json-body-into-keywordized-map
  (with-fake-b2
    (let [auth (b2-auth)]
      (is (= {:api-url (:origin *fake-b2*)
              :auth-token fake-auth-token
              :download-url (str (:origin *fake-b2*) "/download")
              :account-id fake-account-id}
             auth))
      (is (= expected-basic-auth-str
             (:authorization (first (events-of :authorize))))
          "authorize must send Basic key-id:app-key"))))

(deftest b2-bucket-id-resolves-name-and-returns-id
  (with-fake-b2
    (let [auth (b2-auth)]
      (is (= fake-bucket-id (#'store/b2-bucket-id! auth fake-bucket)))
      (let [{:keys [authorization content-type request]}
            (first (events-of :list-buckets))]
        (is (= fake-auth-token authorization) "JSON calls use the session token")
        (is (= "application/json" content-type))
        (is (= {:accountId fake-account-id :bucketName fake-bucket} request)
            "accountId from the authorize response flows into list_buckets")))))

(deftest b2-bucket-id-throws-for-unknown-bucket
  (with-fake-b2
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"B2 bucket not found"
                          (#'store/b2-bucket-id! (b2-auth) "no-such-bucket")))))

(deftest b2-get-upload-url-parses-keywordized-map
  (with-fake-b2
    (let [up (#'store/b2-get-upload-url! (b2-auth) fake-bucket-id)]
      (is (= {:upload-url (str (:origin *fake-b2*) "/upload/u1")
              :auth-token fake-upload-token}
             up))
      (is (= {:bucketId fake-bucket-id}
             (:request (first (events-of :get-upload-url))))))))

(deftest b2-upload-sends-matching-sha1-and-parses-keywordized-response
  (with-fake-b2
    (let [up (#'store/b2-get-upload-url! (b2-auth) fake-bucket-id)
          content (pr-str {:checkpoint 1 :tenant "t"})
          result (#'store/b2-upload! up "kototama-fleet/cp-x.edn" content)]
      ;; The fake rejects any X-Bz-Content-Sha1 that does not match the actual
      ;; request bytes with 400 bad_digest — a green result here IS the proof
      ;; that the upload carried a correct SHA-1 header.
      (is (= "kototama-fleet/cp-x.edn" (:fileName result)))
      (is (= (sha1-hex (utf8-bytes content)) (:contentSha1 result)))
      (is (= (count (utf8-bytes content)) (:contentLength result)))
      (is (= "application/edn" (:contentType result)))
      (let [{:keys [authorization file-name content-type content-length]}
            (first (events-of :upload))]
        (is (= fake-upload-token authorization) "upload uses the upload token")
        (is (= "kototama-fleet/cp-x.edn" file-name))
        (is (= "application/edn" content-type))
        (is (= (count (utf8-bytes content)) content-length))
        (is (= content (get-in @(:state *fake-b2*) [:files "kototama-fleet/cp-x.edn"]))
            "fake stored the exact uploaded bytes under the decoded name")))))

(deftest b2-download-by-name-round-trips-edn-and-404s-to-nil
  (with-fake-b2
    (let [auth (b2-auth)
          up (#'store/b2-get-upload-url! auth fake-bucket-id)
          data {:a 1 :b "two" :c [3 4]}]
      (#'store/b2-upload! up "kototama-fleet/dl.edn" (pr-str data))
      (is (= data (#'store/b2-download-by-name! auth fake-bucket "kototama-fleet/dl.edn")))
      (is (nil? (#'store/b2-download-by-name! auth fake-bucket "kototama-fleet/absent.edn"))
          "missing file (404) reads as nil, not an error"))))

;; ── b2-store end-to-end against the fake ─────────────────────────────────────

(deftest b2-store-save-and-load-round-trip
  (with-fake-b2
    (let [b2 (store/b2-store (b2-creds))
          data {:kototama.fleet/checkpoint-schema 1 :tenant "round-trip"}
          id ((:save! b2) "cp-1" data)]
      (is (= :b2 (:kind b2)))
      (is (= "b2://test-bucket/kototama-fleet/cp-1.edn" id))
      ;; The b2-store load! path downloads by name; note the download happens
      ;; inside this assertion (before the chain assertion below).
      (is (= data ((:load! b2) "cp-1")))
      (testing "lazy auth chain runs exactly once on first save (upload path only)"
        (is (= [:authorize :list-buckets :get-upload-url :upload :download]
               (mapv :op (events))))
        "one authorize + one list_buckets for the whole session, despite save+load")
      (testing "second load reuses the authorized session — download only, no re-auth"
        ((:load! b2) "cp-1")
        (is (= 6 (count (events))))
        (is (= :download (:op (last (events)))))))))

(deftest b2-store-load-returns-nil-for-missing-file-lazily-authorizing
  (with-fake-b2
    (let [b2 (store/b2-store (b2-creds))]
      (is (nil? ((:load! b2) "never-saved")))
      (is (= [:authorize :list-buckets :download] (mapv :op (events)))))))

(deftest b2-store-requires-complete-credentials
  ;; Explicit nil opts override any ambient B2_* env (merge semantics), so
  ;; this is deterministic on machines with real B2 env configured.
  (is (nil? (store/b2-store {:key-id nil :app-key fake-app-key :bucket fake-bucket})))
  (is (nil? (store/b2-store {:key-id fake-key-id :app-key nil :bucket fake-bucket})))
  (is (nil? (store/b2-store {:key-id fake-key-id :app-key fake-app-key :bucket nil}))))

;; ── non-2xx → ex-info with :status (fail loudly, never garbage) ──────────────

(deftest b2-authorize-failure-throws-ex-info-with-status
  (with-fake-b2-failing {:authorize-status 401}
    (let [b2 (store/b2-store (b2-creds))]
      (try
        ((:save! b2) "k" {:x 1})
        (is false "expected b2_authorize_account failure")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"b2_authorize_account failed" (.getMessage e)))
          (is (= 401 (:status (ex-data e)))))))))

(deftest b2-get-upload-url-failure-throws-ex-info
  (with-fake-b2-failing {:get-upload-url-status 503}
    (let [b2 (store/b2-store (b2-creds))]
      (try
        ((:save! b2) "k" {:x 1})
        (is false "expected b2_get_upload_url failure")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"b2_get_upload_url failed" (.getMessage e)))
          (is (= 503 (:status (ex-data e)))))))))

(deftest b2-upload-failure-throws-ex-info
  (with-fake-b2-failing {:upload-status 500}
    (let [b2 (store/b2-store (b2-creds))]
      (try
        ((:save! b2) "k" {:x 1})
        (is false "expected b2 upload failure")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"b2 upload failed" (.getMessage e)))
          (is (= 500 (:status (ex-data e)))))))))

(deftest b2-missing-bucket-throws-before-any-upload
  (with-fake-b2-failing {:list-empty? true}
    (let [b2 (store/b2-store (b2-creds))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"B2 bucket not found"
                            ((:save! b2) "k" {:x 1})))
      (is (empty? (events-of :upload))
          "no upload attempted when bucket resolution fails"))))

(deftest b2-malformed-json-from-authorize-throws-not-garbage
  (with-fake-b2-failing {:authorize-malformed-body? true}
    (let [b2 (store/b2-store (b2-creds))]
      (is (thrown? Exception ((:save! b2) "k" {:x 1}))
          "an unparseable 2xx body must throw, never yield a garbage auth map"))))
