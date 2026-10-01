(ns metabase.metabot.self.subscription
  "Responses adapters for ChatGPT and Grok subscription OAuth. API-key billing is never used as a fallback."
  (:require
   [clojure.string :as str]
   [metabase.llm.oauth :as oauth]
   [metabase.metabot.self.adapter :as adapter]
   [metabase.metabot.self.core :as core]
   [metabase.metabot.self.openai :as openai]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.malli :as mu]))

(set! *warn-on-reflection* true)

(def chatgpt-models
  "Responses models supported through the Codex subscription endpoint."
  (merge (into {} (map (fn [[id model]] [id (assoc model :context-window 272000)]))
               (select-keys openai/supported-models ["gpt-6-astra" "gpt-6.1-sol" "gpt-6-sol" "gpt-6-luna"
                                                     "gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"
                                                     "gpt-5.4" "gpt-5.4-mini" "gpt-5.5"]))
         {"gpt-5.3-codex-spark" {:display-name "GPT-5.3 Codex Spark" :context-window 128000}}))

(mu/defn chatgpt-context-window :- [:maybe :int]
  "The supported subscription model's conservative input context window."
  [model :- [:maybe :string]]
  (get-in chatgpt-models [model :context-window]))

(def grok-models
  "Text and tool-calling models supported through the Grok subscription endpoint."
  {"grok-4.7" {:display-name "Grok 4.7"}
   "grok-4.6" {:display-name "Grok 4.6"}
   "grok-4.5" {:display-name "Grok 4.5"}
   "grok-4.3" {:display-name "Grok 4.3"}
   "grok-build-0.1" {:display-name "Grok Build 0.1"}})

(defn- subscription-auth
  [{:keys [slug]} {:keys [credentials]}]
  (let [{:keys [access-token account-id residency]}
        (oauth/credential! slug (:oauth-credential-id credentials))
        chatgpt? (= slug "chatgpt")
        auth {:url (if chatgpt? "https://chatgpt.com" "https://cli-chat-proxy.grok.com")
              :headers (cond-> {"Authorization" (str "Bearer " access-token)
                                "User-Agent" "Metabase"}
                         chatgpt? (assoc "originator" "metabase")
                         (and chatgpt? account-id) (assoc "ChatGPT-Account-Id" account-id)
                         (and chatgpt? residency (not= residency "no_constraint"))
                         (assoc "x-openai-internal-codex-residency" residency)
                         (not chatgpt?) (assoc "X-XAI-Token-Auth" "xai-grok-cli"
                                               "x-authenticateresponse" "authenticate-response"
                                               "x-grok-client-identifier" "metabase"
                                               "x-grok-client-mode" "cli"
                                               "x-grok-client-version" "0.2.93"))}]
    (if chatgpt?
      auth
      (let [identity (core/request auth {:method :get :url "/v1/user" :as :json})
            user-id (get-in identity [:body :userId])]
        (when-not (and (string? user-id) (not (str/blank? user-id)))
          (throw (ex-info (tru "Unable to identify this Grok subscription account. Please sign in again.")
                          {:status-code 400 :api-error true})))
        (assoc-in auth [:headers "x-userid"] user-id)))))

(defn- subscription-provider
  [slug display-name]
  (adapter/provider
   {:slug slug :display-name display-name :auth subscription-auth
    :errors {401 #(tru "Your subscription sign-in has expired. Please sign in again.")
             402 #(tru "Your subscription does not include access to this model. Check your subscription tier.")
             403 #(tru "Your subscription does not include access to this model. Check your subscription tier.")
             429 #(tru "Your subscription usage limit has been reached. Please try again later.")}}))

(def ^:private chatgpt-provider (subscription-provider "chatgpt" "ChatGPT"))
(def ^:private grok-provider (subscription-provider "grok" "Grok"))

(defn- subscription-model-listing
  [catalog id-key supported]
  (when-not (and (sequential? catalog)
                 (every? (fn [entry]
                           (and (map? entry) (string? (id-key entry)) (not (str/blank? (id-key entry)))))
                         catalog))
    (throw (ex-info (tru "The subscription provider returned an invalid model catalog.")
                    {:status-code 400 :api-error true})))
  (let [listed (adapter/model-listing supported (map #(assoc % :id (id-key %)) catalog))]
    (when (and (seq catalog) (empty? (:models listed)))
      (throw (ex-info (tru "Your subscription offers models that Metabot does not support yet. Update Metabase or choose another provider.")
                      {:status-code 400 :api-error true})))
    listed))

(defn- list-subscription-models
  [provider opts path catalog-key id-key supported]
  (adapter/reject-ai-proxy! provider (:ai-proxy? opts))
  (try
    (let [response (adapter/request! provider {:method :get :path path :as :json
                                               :credentials (:credentials opts)})
          catalog (get (:body response) catalog-key)]
      (subscription-model-listing catalog id-key supported))
    (catch Exception e
      (adapter/rethrow! provider e))))

(mu/defn list-chatgpt-models :- adapter/ModelListing
  "List the Responses models available to the signed-in ChatGPT account."
  ([] (list-chatgpt-models {}))
  ([opts :- adapter/ListOpts]
   (list-subscription-models chatgpt-provider opts "/backend-api/codex/models?client_version=0.159.3"
                             :models :slug chatgpt-models)))

(mu/defn list-grok-models :- adapter/ModelListing
  "List the tool-calling models available to the signed-in Grok account."
  ([] (list-grok-models {}))
  ([opts :- adapter/ListOpts]
   (list-subscription-models grok-provider opts "/v1/models" :data #(or (:id %) (:model %)) grok-models)))

(defn chatgpt-request-body
  "Build a Codex subscription request. Its endpoint does not accept output limits or temperature."
  [opts]
  (-> (openai/openai-request-body opts)
      (dissoc :max_output_tokens :temperature)
      (update :instructions #(or % ""))))

(defn grok-request-body
  "Build a Grok Responses request without OpenAI-only reasoning replay parameters."
  [opts]
  (-> (openai/openai-request-body (assoc opts :reasoning? false))
      (dissoc :include :reasoning)))

(mu/defn chatgpt
  "Call ChatGPT using the subscription Responses endpoint."
  [opts :- core/LLMRequestOpts]
  (eduction (openai/openai->aisdk-chunks-xf)
            (adapter/stream! chatgpt-provider opts
                             {:path "/backend-api/codex/responses" :body (chatgpt-request-body opts)
                              :headers {"Accept" "text/event-stream"}})))

(mu/defn grok
  "Call Grok using the subscription Responses endpoint."
  [opts :- core/LLMRequestOpts]
  (eduction (openai/openai->aisdk-chunks-xf)
            (adapter/stream! grok-provider opts
                             {:path "/v1/responses" :body (grok-request-body opts)
                              :headers {"Accept" "text/event-stream"}})))
