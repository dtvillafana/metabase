(ns metabase.metabot.self.subscription-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [metabase.llm.oauth :as oauth]
   [metabase.metabot.self.adapter :as adapter]
   [metabase.metabot.self.core :as core]
   [metabase.metabot.self.subscription :as subscription]
   [metabase.test :as mt]
   [metabase.util.json :as json]))

(set! *warn-on-reflection* true)

(def ^:private opts
  {:model "gpt-5.4" :system "Answer helpfully." :input [{:role :user :content "Hello"}]
   :credentials {:oauth-credential-id "credential"} :max-tokens 32 :temperature 0.5})

(deftest ^:parallel request-bodies-test
  (testing "Codex requests preserve tool calling but omit unsupported generation parameters"
    (let [body (subscription/chatgpt-request-body (assoc opts :schema {:type "object"}))]
      (is (false? (:store body)))
      (is (true? (:stream body)))
      (is (= "required" (:tool_choice body)))
      (is (= "structured_output" (get-in body [:tools 0 :name])))
      (is (nil? (:max_output_tokens body)))
      (is (nil? (:temperature body)))
      (is (= "" (:instructions (subscription/chatgpt-request-body (dissoc opts :system)))))))
  (testing "Grok requests do not carry OpenAI-only encrypted reasoning"
    (let [body (subscription/grok-request-body (assoc opts :model "grok-4.6"))]
      (is (= "grok-4.6" (:model body)))
      (is (false? (:store body)))
      (is (nil? (:include body)))
      (is (nil? (:reasoning body))))))

(deftest ^:parallel current-model-request-bodies-test
  (doseq [model ["gpt-6-astra" "gpt-6.1-sol" "gpt-6-sol" "gpt-6-luna"]]
    (let [body (subscription/chatgpt-request-body (assoc opts :model model :schema {:type "object"}))]
      (is (= model (:model body)))
      (is (= "required" (:tool_choice body)))
      (is (= {:summary "auto"} (:reasoning body)))
      (is (not (contains? body :temperature)))
      (is (not (contains? body :max_output_tokens)))
      (is (= 272000 (subscription/chatgpt-context-window model)))))
  (let [body (subscription/grok-request-body (assoc opts :model "grok-4.7" :schema {:type "object"}))]
    (is (= "grok-4.7" (:model body)))
    (is (= "required" (:tool_choice body)))
    (is (not (contains? body :reasoning)))
    (is (not (contains? body :include)))))

(deftest catalog-and-auth-test
  (mt/with-dynamic-fn-redefs [oauth/credential! (fn [type-name id]
                                                  (is (#{"chatgpt" "grok"} type-name))
                                                  (is (= "credential" id))
                                                  {:access-token "subscription-access" :account-id "account" :residency "us"})
                              core/request (fn [auth req]
                                             (is (= "Bearer subscription-access" (get-in auth [:headers "Authorization"])))
                                             (case (:url req)
                                               "/backend-api/codex/models?client_version=0.159.3"
                                               (do
                                                 (is (= "https://chatgpt.com" (:url auth)))
                                                 (is (= "account" (get-in auth [:headers "ChatGPT-Account-Id"])))
                                                 (is (= "us" (get-in auth [:headers "x-openai-internal-codex-residency"])))
                                                 {:body {:models [{:slug "gpt-5.4"} {:slug "gpt-5.5-pro"}]}})
                                               "/v1/user" {:body {:userId "verified-user"}}
                                               "/v1/models"
                                               (do
                                                 (is (= "https://cli-chat-proxy.grok.com" (:url auth)))
                                                 (is (= "verified-user" (get-in auth [:headers "x-userid"])))
                                                 (is (= "metabase" (get-in auth [:headers "x-grok-client-identifier"])))
                                                 {:body {:data [{:model "grok-4.6"} {:id "grok-4.5"} {:id "grok-imagine"}]}})))]
    (is (= {:models [{:id "gpt-5.4" :display_name "GPT-5.4"}]}
           (subscription/list-chatgpt-models (select-keys opts [:credentials]))))
    (is (= {:models [{:id "grok-4.5" :display_name "Grok 4.5"} {:id "grok-4.6" :display_name "Grok 4.6"}]}
           (subscription/list-grok-models (select-keys opts [:credentials]))))))

(deftest latest-subscription-models-test
  (doseq [[list-models catalog expected]
          [[subscription/list-chatgpt-models
            {:models (mapv #(hash-map :slug %) ["gpt-6-astra" "gpt-6.1-sol" "gpt-6-sol" "gpt-6-luna"
                                                "gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"])}
            #{"gpt-6-astra" "gpt-6.1-sol" "gpt-6-sol" "gpt-6-luna"
              "gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"}]
           [subscription/list-grok-models
            {:data [{:id "grok-4.7"} {:model "grok-4.6"} {:id "grok-4.5"}]}
            #{"grok-4.7" "grok-4.6" "grok-4.5"}]]]
    (mt/with-dynamic-fn-redefs [adapter/request! (fn [& _] {:body catalog})]
      (is (= expected (set (map :id (:models (list-models (select-keys opts [:credentials]))))))))))

(deftest subscription-catalog-errors-test
  (doseq [[list-models catalog-key id-key] [[subscription/list-chatgpt-models :models :slug]
                                            [subscription/list-grok-models :data :id]]]
    (testing "a genuinely empty catalog is distinct from an unsupported catalog"
      (mt/with-dynamic-fn-redefs [adapter/request! (fn [& _] {:body {catalog-key []}})]
        (is (= {:models []} (list-models (select-keys opts [:credentials]))))))
    (testing "an unknown model must not be misreported as a subscription entitlement failure"
      (mt/with-dynamic-fn-redefs [adapter/request! (fn [& _] {:body {catalog-key [{id-key "future-model"}]}})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"models that Metabot does not support"
                              (list-models (select-keys opts [:credentials]))))))
    (testing "malformed catalog entries fail instead of silently disappearing"
      (doseq [entry [{} {id-key ""} {id-key 123} "not-a-model"]]
        (mt/with-dynamic-fn-redefs [adapter/request! (fn [& _] {:body {catalog-key [entry]}})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid model catalog"
                                (list-models (select-keys opts [:credentials])))))))))

(deftest streaming-endpoints-test
  (mt/with-dynamic-fn-redefs [oauth/credential! (fn [& _] {:access-token "subscription-access"})
                              core/request (fn [auth req]
                                             (let [body (json/decode+kw (:body req))]
                                               (is (= "https://chatgpt.com" (:url auth)))
                                               (is (= "/backend-api/codex/responses" (:url req)))
                                               (is (= "text/event-stream" (get-in req [:headers "Accept"])))
                                               (is (false? (:store body)))
                                               (is (nil? (:max_output_tokens body)))
                                               {:body (java.io.ByteArrayInputStream.
                                                       (.getBytes "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{}}}\n\n" "UTF-8"))}))]
    (is (= :usage (:type (first (into [] (subscription/chatgpt opts))))))))

(deftest proxy-refusal-test
  (mt/with-dynamic-fn-redefs [oauth/credential! (fn [& _] (throw (AssertionError. "Must not resolve OAuth tokens")))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"AI proxy is not supported"
                          (subscription/list-chatgpt-models {:ai-proxy? true})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"AI proxy is not supported"
                          (subscription/grok (assoc opts :ai-proxy? true))))))

(deftest subscription-entitlement-error-test
  (mt/with-dynamic-fn-redefs [adapter/request! (fn [& _] (throw (ex-info "upstream error" {:status 403 :body "sensitive"})))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"subscription tier"
                          (subscription/list-chatgpt-models (select-keys opts [:credentials]))))))
