(ns metabase.llm.oauth-test
  (:require
   [clj-http.client :as http]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [metabase.app-db.cluster-lock :as cluster-lock]
   [metabase.llm.oauth :as oauth]
   [metabase.llm.provider.settings :as provider.settings]
   [metabase.test :as mt]
   [metabase.util.json :as json]))

(set! *warn-on-reflection* true)

(defn- with-store!
  [initial f]
  (let [store (atom initial)]
    (mt/with-dynamic-fn-redefs [oauth/entries (fn [] @store)
                                oauth/save! (fn [entries] (reset! store entries))
                                cluster-lock/do-with-cluster-lock (fn [_ thunk] (locking store (thunk)))]
      (f store))))

(defn- response
  [status body]
  {:status status :body (json/encode body)})

(def ^:private tokens {:access_token "access-secret" :refresh_token "refresh-secret" :expires_in 3600})

(defn- pending-entry
  [type-name]
  {:id "flow" :type type-name :user-id 1 :device-code "device-secret" :user-code "ABCD"
   :deadline (+ (System/currentTimeMillis) 600000) :next-poll 0 :interval 8})

(defn- credential-entry
  []
  {:id "credential" :type "grok" :user-id 1 :access-token "old-access"
   :refresh-token "old-refresh" :expires-at 0})

(deftest device-start-test
  (doseq [type-name ["chatgpt" "grok"]]
    (with-store! []
      (fn [store]
        (mt/with-dynamic-fn-redefs [provider.settings/llm-request-opts (fn [& _] {})
                                    http/request (fn [req]
                                                   (is (false? (:follow-redirects req)))
                                                   (is (= 15000 (:socket-timeout req)))
                                                   (if (= type-name "chatgpt")
                                                     (do
                                                       (is (= "https://auth.openai.com/api/accounts/deviceauth/usercode" (:url req)))
                                                       (is (= {:client_id "app_EMoamEEZ73f0CkXaXp7hrann"} (json/decode+kw (:body req))))
                                                       (response 200 {:device_auth_id "device-secret" :user_code "ABCD" :interval "5"}))
                                                     (do
                                                       (is (= "https://auth.x.ai/oauth2/device/code" (:url req)))
                                                       (is (string? (get-in req [:form-params :scope])))
                                                       (response 200 {:device_code "device-secret" :user_code "ABCD"
                                                                      :verification_uri "https://accounts.x.ai/oauth2/device"
                                                                      :expires_in 300 :interval 5}))))]
          (let [result (oauth/start! type-name 1)]
            (is (= "ABCD" (:user_code result)))
            (is (= 8 (:interval result)))
            (is (= (:flow_id result) (:id (first @store))))
            (is (= "device-secret" (:device-code (first @store))))
            (is (not (re-find #"device-secret|access-secret|refresh-secret" (pr-str result))))))))))

(deftest untrusted-verification-url-test
  (with-store! []
    (fn [store]
      (mt/with-dynamic-fn-redefs [oauth/request! (fn [& _] {:status 200 :body {:device_code "device" :user_code "ABCD"
                                                                               :verification_uri "https://evil.example/steal"}})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid device code" (oauth/start! "grok" 1)))
        (is (empty? @store))))))

(deftest polling-ownership-and-expiry-test
  (with-store! [(pending-entry "grok")]
    (fn [store]
      (mt/with-dynamic-fn-redefs [oauth/request! (fn [& _] (throw (AssertionError. "Must not contact provider")))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expired" (oauth/poll! "grok" 2 "flow")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expired" (oauth/poll! "chatgpt" 1 "flow")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expired" (oauth/poll! "grok" 1 "unknown")))
        (swap! store assoc-in [0 :deadline] 0)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expired" (oauth/poll! "grok" 1 "flow")))))))

(deftest pending-and-slow-down-test
  (doseq [[error interval] [["authorization_pending" 8] ["slow_down" 13]]]
    (with-store! [(pending-entry "grok")]
      (fn [store]
        (let [requests (atom 0)]
          (mt/with-dynamic-fn-redefs [oauth/request! (fn [& _] (swap! requests inc) {:status 400 :body {:error error}})]
            (is (= {:status "pending"} (oauth/poll! "grok" 1 "flow")))
            (is (= interval (:interval (first @store))))
            (is (= {:status "pending"} (oauth/poll! "grok" 1 "flow")))
            (is (= 1 @requests) "Repeated browser polls do not hammer the issuer")))))))

(deftest successful-login-and-activation-test
  (doseq [type-name ["chatgpt" "grok"]]
    (with-store! [(pending-entry type-name)]
      (fn [store]
        (let [requests (atom [])]
          (mt/with-dynamic-fn-redefs [oauth/request! (fn [_ path params json?]
                                                       (swap! requests conj [path params json?])
                                                       {:status 200 :body (if (str/ends-with? path "/deviceauth/token")
                                                                            {:authorization_code "code" :code_verifier "verifier"}
                                                                            tokens)})]
            (is (= {:status "authorized" :credential_id "flow"} (oauth/poll! type-name 1 "flow")))
            (is (= "access-secret" (:access-token (first @store))))
            (is (nil? (:device-code (first @store))))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Complete subscription" (oauth/check-authorization! type-name 2 "flow")))
            (oauth/check-authorization! type-name 1 "flow")
            (oauth/activate! type-name 1 "flow" (fn [] nil))
            (is (nil? (:deadline (first @store))))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Complete subscription" (oauth/check-authorization! type-name 1 "flow")))
            (oauth/cancel! type-name 1 "flow")
            (is (= 1 (count @store)) "Unmount cleanup cannot delete a connected credential")
            (when (= type-name "chatgpt")
              (is (= ["/oauth/token"
                      {:grant_type "authorization_code" :client_id "app_EMoamEEZ73f0CkXaXp7hrann"
                       :code "code" :code_verifier "verifier"
                       :redirect_uri "https://auth.openai.com/deviceauth/callback"} false]
                     (second @requests))))))))))

(deftest refresh-rotation-test
  (with-store! [(credential-entry)]
    (fn [store]
      (let [calls (atom 0)]
        (mt/with-dynamic-fn-redefs [oauth/request! (fn [_ path params json?]
                                                     (swap! calls inc)
                                                     (is (= "/oauth2/token" path))
                                                     (is (= "old-refresh" (:refresh_token params)))
                                                     (is (false? json?))
                                                     {:status 200 :body tokens})]
          (is (= "access-secret" (:access-token (oauth/credential! "grok" "credential"))))
          (is (= "refresh-secret" (:refresh-token (first @store))))
          (is (= "access-secret" (:access-token (oauth/credential! "grok" "credential"))))
          (is (= 1 @calls)))))))

(deftest concurrent-refresh-test
  (testing "Concurrent requests resolve the persisted token instead of consuming a refresh token twice"
    (with-store! [(credential-entry)]
      (fn [_]
        (let [calls (atom 0)]
          (mt/with-dynamic-fn-redefs [oauth/request! (fn [& _] (swap! calls inc) {:status 200 :body tokens})]
            (let [requests (doall (repeatedly 8 #(future (oauth/credential! "grok" "credential"))))]
              (is (every? #(= "access-secret" (:access-token @%)) requests))
              (is (= 1 @calls)))))))))

(deftest refresh-without-rotated-refresh-token-test
  (with-store! [(credential-entry)]
    (fn [store]
      (mt/with-dynamic-fn-redefs [oauth/request! (fn [& _] {:status 200 :body (dissoc tokens :refresh_token)})]
        (oauth/credential! "grok" "credential")
        (is (= "old-refresh" (:refresh-token (first @store))))))))

(deftest malformed-token-response-test
  (with-store! [(credential-entry)]
    (fn [store]
      (mt/with-dynamic-fn-redefs [oauth/request! (fn [& _] {:status 200 :body {:access_token 123 :refresh_token 456}})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"renewable credentials" (oauth/credential! "grok" "credential")))
        (is (= "old-refresh" (:refresh-token (first @store))))))))

(deftest expired-pending-credential-is-not-usable-test
  (with-store! [(assoc (credential-entry) :deadline 0)]
    (fn [_]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disconnected" (oauth/credential! "grok" "credential"))))))

(deftest cancel-and-disconnect-test
  (with-store! [(pending-entry "grok") (credential-entry)]
    (fn [store]
      (oauth/cancel! "grok" 2 "flow")
      (is (= 2 (count @store)))
      (oauth/cancel! "grok" 1 "flow")
      (is (= ["credential"] (map :id @store)))
      (oauth/disconnect! "credential")
      (is (empty? @store))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disconnected" (oauth/credential! "grok" "credential"))))))

(deftest authorization-errors-never-carry-secrets-test
  (with-store! [(credential-entry)]
    (fn [_]
      (mt/with-dynamic-fn-redefs [provider.settings/llm-request-opts (fn [& _] {})
                                  http/request (fn [_] (throw (ex-info "secret in upstream exception" {:refresh_token "old-refresh"})))]
        (try
          (oauth/credential! "grok" "credential")
          (is false "Expected sanitized error")
          (catch clojure.lang.ExceptionInfo e
            (is (nil? (.getCause e)))
            (is (not (re-find #"old-refresh|upstream exception" (str e (ex-data e)))))))))))
