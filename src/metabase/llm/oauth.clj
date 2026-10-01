(ns metabase.llm.oauth
  "Device authorization and renewable credentials for subscription-backed LLM connections."
  (:require
   [clj-http.client :as http]
   [clojure.string :as str]
   [metabase.app-db.cluster-lock :as cluster-lock]
   [metabase.llm.provider.settings :as provider.settings]
   [metabase.settings.core :as setting]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.json :as json])
  (:import
   (java.util Base64 UUID)))

(set! *warn-on-reflection* true)

(def providers
  "Pinned OAuth issuers and public device clients. Credentials never travel to a configurable origin."
  {"chatgpt" {:client-id "app_EMoamEEZ73f0CkXaXp7hrann"
              :issuer "https://auth.openai.com"
              :token-path "/oauth/token"}
   "grok"    {:client-id "b1a00492-073a-47ea-816f-4c329264a828"
              :issuer "https://auth.x.ai"
              :token-path "/oauth2/token"}})

(defn- fail!
  [message]
  (throw (ex-info message {:status-code 400 :api-error true})))

(defn- provider!
  [type-name]
  (or (get providers type-name)
      (fail! (tru "This provider does not support subscription sign-in."))))

(defn- seconds
  [value fallback]
  (try
    (let [value (Long/parseLong (str value))]
      (if (pos? value) (min value 86400) fallback))
    (catch NumberFormatException _ fallback)))

(defn- request!
  [type-name path params json?]
  (let [{:keys [issuer]} (provider! type-name)
        response (try
                   (http/request
                    (merge (provider.settings/llm-request-opts nil issuer)
                           {:method :post :url (str issuer path)
                            :connection-timeout 10000 :socket-timeout 15000
                            :follow-redirects false :throw-exceptions false :as :text
                            :headers {"Accept" "application/json" "User-Agent" "Metabase"}}
                           (if json?
                             {:content-type :json :body (json/encode params)}
                             {:form-params params})))
                   ;; HTTP exception data can contain the submitted refresh token. Never attach it to an API error.
                   (catch Exception _
                     (fail! (tru "Unable to reach the subscription sign-in service. Please try again."))))]
    {:status (:status response)
     :body (try (json/decode+kw (:body response))
                (catch Exception _
                  (fail! (tru "The subscription sign-in service returned an invalid response."))))}))

(defn- successful-body!
  [{:keys [status body]}]
  (if (<= 200 status 299)
    body
    (fail! (tru "Subscription authorization failed (HTTP {0}). Please sign in again." status))))

(defn- entries
  []
  ;; A sibling instance may have rotated a single-use refresh token since our last settings-cache poll.
  (setting/restore-cache!)
  (vec (provider.settings/llm-oauth-credentials)))

(defn- save!
  [entries]
  (setting/set-value-of-type! :json :llm-oauth-credentials entries))

(defn- replace-entry
  [entries entry]
  (conj (vec (remove #(= (:id %) (:id entry)) entries)) entry))

(defn start!
  "Start a device login owned by `user-id`. Returns only the public verification details and an opaque flow ID."
  [type-name user-id]
  (let [{:keys [client-id]} (provider! type-name)
        chatgpt? (= type-name "chatgpt")
        device (successful-body!
                (request! type-name
                          (if chatgpt? "/api/accounts/deviceauth/usercode" "/oauth2/device/code")
                          (cond-> {:client_id client-id}
                            (not chatgpt?) (assoc :scope "openid profile email offline_access grok-cli:access api:access conversations:read conversations:write"))
                          chatgpt?))
        device-code (if chatgpt? (:device_auth_id device) (:device_code device))
        url (if chatgpt? "https://auth.openai.com/codex/device" (:verification_uri device))
        id (str (UUID/randomUUID))
        expires-in (min 600 (seconds (:expires_in device) 600))
        interval (+ 3 (seconds (:interval device) 5))]
    (when-not (and (string? device-code) (not (str/blank? device-code))
                   (string? (:user_code device))
                   (string? url)
                   ;; Do not render an arbitrary verification origin returned by an upstream service.
                   (re-matches #"https://(?:auth\.openai\.com|auth\.x\.ai|accounts\.x\.ai)/.*" url))
      (fail! (tru "The subscription sign-in service returned an invalid device code.")))
    (cluster-lock/with-cluster-lock ::credentials
      (let [now (System/currentTimeMillis)
            entry {:id id :type type-name :user-id user-id :device-code device-code
                   :user-code (:user_code device) :deadline (+ now (* expires-in 1000))
                   :interval interval :next-poll (+ now (* interval 1000))}]
        (save! (conj (vec (remove #(and (:deadline %)
                                        (or (<= (:deadline %) now)
                                            (and (= user-id (:user-id %)) (= type-name (:type %)))))
                                  (entries)))
                     entry))))
    {:flow_id id :verification_url url :user_code (:user_code device)
     :expires_in expires-in :interval interval}))

(defn- jwt-claims
  [token]
  (try
    (when-let [payload (second (str/split (or token "") #"\."))]
      (json/decode+kw (String. (.decode (Base64/getUrlDecoder) ^String payload) "UTF-8")))
    (catch Exception _ nil)))

(defn- token-entry
  [entry tokens]
  (let [claims (or (jwt-claims (:id_token tokens)) (jwt-claims (:access_token tokens)))
        auth (get claims (keyword "https://api.openai.com/auth"))
        refresh-token (or (when (string? (:refresh_token tokens)) (not-empty (:refresh_token tokens)))
                          (:refresh-token entry))]
    (when-not (and (string? (:access_token tokens)) (not (str/blank? (:access_token tokens)))
                   (string? refresh-token) (not (str/blank? refresh-token)))
      (fail! (tru "The subscription sign-in service did not return renewable credentials.")))
    (cond-> (-> entry
                (dissoc :device-code :user-code :next-poll :interval)
                (assoc :access-token (:access_token tokens) :refresh-token refresh-token
                       :expires-at (+ (System/currentTimeMillis) (* 1000 (seconds (:expires_in tokens) 3600)))))
      (= "chatgpt" (:type entry))
      (assoc :account-id (or (:chatgpt_account_id claims) (:chatgpt_account_id auth)
                             (get-in claims [:organizations 0 :id]) (:account-id entry))
             :residency (or (:chatgpt_compute_residency auth) (:chatgpt_compute_residency claims)
                            (:residency entry))))))

(defn poll!
  "Poll a login owned by `user-id`. Returns pending or an opaque credential reference, never tokens."
  [type-name user-id flow-id]
  (cluster-lock/with-cluster-lock ::credentials
    (let [all (entries)
          entry (some #(when (and (= flow-id (:id %)) (= type-name (:type %))
                                  (= user-id (:user-id %))) %) all)
          now (System/currentTimeMillis)
          {:keys [client-id token-path]} (provider! type-name)]
      (when-not (and entry (> (:deadline entry 0) now))
        (fail! (tru "This sign-in has expired. Please start again.")))
      (cond
        (:access-token entry) {:status "authorized" :credential_id flow-id}
        (> (:next-poll entry) now) {:status "pending"}
        :else
        (let [chatgpt? (= type-name "chatgpt")
              {:keys [status body]} (request! type-name
                                              (if chatgpt? "/api/accounts/deviceauth/token" token-path)
                                              (if chatgpt?
                                                {:device_auth_id (:device-code entry) :user_code (:user-code entry)}
                                                {:grant_type "urn:ietf:params:oauth:grant-type:device_code"
                                                 :client_id client-id :device_code (:device-code entry)})
                                              chatgpt?)]
          (if (<= 200 status 299)
            (let [tokens (if chatgpt?
                           (successful-body!
                            (request! type-name token-path
                                      {:grant_type "authorization_code" :client_id client-id
                                       :code (:authorization_code body) :code_verifier (:code_verifier body)
                                       :redirect_uri "https://auth.openai.com/deviceauth/callback"} false))
                           body)]
              (save! (replace-entry all (token-entry entry tokens)))
              {:status "authorized" :credential_id flow-id})
            (if (or (and chatgpt? (#{403 404} status))
                    (#{"authorization_pending" "slow_down"} (:error body)))
              (let [interval (+ (:interval entry) (if (= "slow_down" (:error body)) 5 0))]
                (save! (replace-entry all (assoc entry :interval interval :next-poll (+ now (* interval 1000)))))
                {:status "pending"})
              (do
                (save! (vec (remove #(= flow-id (:id %)) all)))
                (fail! (tru "Subscription sign-in was denied or expired. Please start again."))))))))))

(defn check-authorization!
  "Check that a renewable, unexpired login belongs to the administrator creating a connection."
  [type-name user-id credential-id]
  (cluster-lock/with-cluster-lock ::credentials
    (let [all (entries)
          entry (some #(when (= credential-id (:id %)) %) all)]
      (when-not (and (= type-name (:type entry)) (= user-id (:user-id entry))
                     (:access-token entry) (> (:deadline entry 0) (System/currentTimeMillis)))
        (fail! (tru "Complete subscription sign-in before connecting this provider.")))
      nil)))

(defn activate!
  "Save a connection and retain its authorized credential under the same lock. Calls `save-connection!` once."
  [type-name user-id credential-id save-connection!]
  (cluster-lock/with-cluster-lock ::credentials
    (let [all (entries)
          entry (some #(when (and (= credential-id (:id %)) (= type-name (:type %))
                                  (= user-id (:user-id %))) %) all)]
      (when-not (and (:access-token entry) (> (:deadline entry 0) (System/currentTimeMillis)))
        (fail! (tru "Complete subscription sign-in before connecting this provider.")))
      (save-connection!)
      (save! (replace-entry all (dissoc entry :deadline))))))

(defn cancel!
  "Cancel a pending login. Connected credentials and another administrator's login are never removed."
  [type-name user-id flow-id]
  (cluster-lock/with-cluster-lock ::credentials
    (save! (vec (remove #(and (= flow-id (:id %)) (= type-name (:type %))
                              (= user-id (:user-id %)) (:deadline %))
                        (entries))))))

(defn disconnect!
  "Remove a saved credential or cancel a pending login."
  [credential-id]
  (cluster-lock/with-cluster-lock ::credentials
    (save! (vec (remove #(= credential-id (:id %)) (entries))))))

(defn credential!
  "Resolve and, if needed, refresh a subscription credential. Rotated tokens are persisted before use."
  [type-name credential-id]
  (cluster-lock/with-cluster-lock ::credentials
    (let [all (entries)
          entry (some #(when (and (= credential-id (:id %)) (= type-name (:type %))) %) all)]
      (when-not (and (:access-token entry)
                     (or (not (:deadline entry)) (> (:deadline entry) (System/currentTimeMillis))))
        (fail! (tru "This subscription is disconnected. Please sign in again.")))
      (if (> (:expires-at entry 0) (+ (System/currentTimeMillis) 120000))
        entry
        (let [{:keys [client-id token-path]} (provider! type-name)
              tokens (successful-body!
                      (request! type-name token-path
                                {:grant_type "refresh_token" :refresh_token (:refresh-token entry)
                                 :client_id client-id} false))
              refreshed (token-entry entry tokens)]
          (save! (replace-entry all refreshed))
          refreshed)))))
