(ns metabase.premium-features.self-hosted-test
  (:require
   [clojure.test :refer :all]
   [metabase.config.core :as config]
   [metabase.premium-features.core :as premium-features]
   [metabase.premium-features.settings :as premium-features.settings]
   [metabase.premium-features.token-check :as token-check]
   [metabase.test :as mt]))

(set! *warn-on-reflection* true)

(deftest self-hosted-features-without-token-test
  (with-redefs [config/ee-available? true]
    (mt/with-dynamic-fn-redefs [premium-features.settings/premium-embedding-token (constantly nil)
                                token-check/check-token (fn [& _] (throw (ex-info "Unexpected token check" {})))]
      (testing "Local features are available without contacting the license server"
        (doseq [feature [:advanced-permissions :audit-app :sandboxes :sso-jwt :sso-saml :sso-oidc :scim
                         :multi-factor-auth :serialization :whitelabel :embedding-sdk :remote-sync :library
                         :dependencies :transforms-basic :writable-connection :custom-viz :schema-viewer]]
          (is (true? (premium-features/has-feature? feature)) (name feature))
          (is (true? (premium-features/canonically-has-feature? feature)) (name feature))
          (is (nil? (premium-features/assert-has-feature feature (name feature))))))
      (testing "Cloud services and development watermarks are not granted locally"
        (doseq [feature [:hosting :attached-dwh :cloud-custom-smtp :etl-connections :etl-connections-pg
                         :metabot-v3 :metabase-ai-managed :offer-metabase-ai-managed :support-users
                         :admin-security-center :development-mode :unknown-feature]]
          (is (false? (premium-features/has-feature? feature)) (name feature))))
      (testing "The status consumed by the frontend is non-trial and includes local entitlements"
        (is (=? {:valid true :canonical? true :trial false :status "self-hosted"}
                (premium-features/token-status)))
        (is (contains? (set (:features (premium-features/token-status))) "sandboxes"))
        (is (true? (premium-features.settings/enable-sandboxes?)))
        (is (false? (premium-features.settings/is-hosted?)))))))

(deftest self-hosted-user-limits-test
  (with-redefs [config/ee-available? true]
    (mt/with-dynamic-fn-redefs [premium-features.settings/premium-embedding-token (constantly "airgap_old-token")
                                token-check/decode-airgap-token (fn [& _] (throw (ex-info "Unexpected token decode" {})))]
      (is (nil? (premium-features/max-users-allowed)))
      (is (nil? (premium-features/assert-valid-airgap-user-count!)))
      (is (nil? (premium-features/assert-airgap-allows-user-creation!))))))

(deftest self-hosted-features-with-invalid-token-test
  (with-redefs [config/ee-available? true]
    (mt/with-dynamic-fn-redefs [premium-features.settings/premium-embedding-token (constantly "expired-token")
                                token-check/check-token (constantly {:valid false :canonical? true :status "expired"})]
      (is (true? (premium-features/has-feature? :sandboxes)))
      (is (true? (premium-features/canonically-has-feature? :dependencies)))
      (is (false? (premium-features/has-feature? :metabase-ai-managed)))
      (is (false? (premium-features/is-trial?))))))

(deftest hosted-features-unchanged-test
  (let [status {:valid true :canonical? true :status "valid" :trial true :features ["hosting" "audit-app"]}]
    (with-redefs [config/ee-available? true]
      (mt/with-dynamic-fn-redefs [premium-features.settings/premium-embedding-token (constantly "cloud-token")
                                  token-check/check-token (constantly status)]
        (is (= status (premium-features/token-status)))
        (is (true? (premium-features/has-feature? :hosting)))
        (is (false? (premium-features/has-feature? :sandboxes)))
        (is (true? (premium-features/is-trial?)))))))

(deftest external-service-status-remains-indeterminate-test
  (with-redefs [config/ee-available? true]
    (mt/with-dynamic-fn-redefs [premium-features.settings/premium-embedding-token (constantly "unreachable-token")
                                token-check/check-token (constantly {:valid false :canonical? false :status "unavailable"})]
      (is (true? (premium-features/canonically-has-feature? :sandboxes)))
      (doseq [feature [:hosting :metabot-v3 :metabase-ai-managed]]
        (is (nil? (premium-features/canonically-has-feature? feature)))))))

(deftest missing-enterprise-code-test
  (with-redefs [config/ee-available? false]
    (mt/with-dynamic-fn-redefs [premium-features.settings/premium-embedding-token (constantly nil)]
      (is (empty? (premium-features.settings/self-hosted-features)))
      (is (false? (premium-features/has-feature? :sandboxes)))
      (is (nil? (premium-features/token-status))))))
