import { useEffect, useRef, useState } from "react";
import { t } from "ttag";

import {
  useCancelLlmOAuthMutation,
  useCreateLlmProviderMutation,
  usePollLlmOAuthMutation,
  useStartLlmOAuthMutation,
  useUpdateLlmProviderMutation,
} from "metabase/api";
import { getErrorMessage } from "metabase/api/utils";
import { Anchor, Button, Group, Stack, Text, TextInput } from "metabase/ui";
import type {
  LlmOAuthStartResponse,
  LlmProviderConnection,
  LlmProviderType,
} from "metabase-types/api";

export function OAuthProviderConnection({
  providerType,
  connection,
  onSaved,
  onCancel,
}: {
  providerType: LlmProviderType;
  connection?: LlmProviderConnection;
  onSaved: (connection: LlmProviderConnection) => void;
  onCancel?: () => void;
}) {
  const [name, setName] = useState(connection?.name ?? providerType.label);
  const [flow, setFlow] = useState<LlmOAuthStartResponse>();
  const [credentialId, setCredentialId] = useState<string>();
  const [error, setError] = useState<string>();
  const [start, startResult] = useStartLlmOAuthMutation();
  const [poll] = usePollLlmOAuthMutation();
  const [cancel] = useCancelLlmOAuthMutation();
  const [create, createResult] = useCreateLlmProviderMutation();
  const [update, updateResult] = useUpdateLlmProviderMutation();
  const pendingFlow = useRef<string>();
  const isMounted = useRef(true);
  const isSaving = createResult.isLoading || updateResult.isLoading;

  useEffect(() => {
    isMounted.current = true;
    return () => {
      isMounted.current = false;
      if (pendingFlow.current) {
        void cancel({ type: providerType.type, flow_id: pendingFlow.current });
      }
    };
  }, [cancel, providerType.type]);

  useEffect(() => {
    if (!flow || credentialId) {
      return;
    }
    let stopped = false;
    let timeout: ReturnType<typeof setTimeout>;
    const deadline = Date.now() + flow.expires_in * 1000;
    const checkAuthorization = async () => {
      if (Date.now() >= deadline) {
        setError(t`Sign-in expired. Please start again.`);
        setFlow(undefined);
        return;
      }
      try {
        const result = await poll({
          type: providerType.type,
          flow_id: flow.flow_id,
        }).unwrap();
        if (stopped) {
          return;
        }
        if (result.status === "authorized") {
          setCredentialId(result.credential_id);
        } else {
          timeout = setTimeout(checkAuthorization, flow.interval * 1000);
        }
      } catch (caught) {
        if (!stopped) {
          setError(getErrorMessage(caught, t`Unable to complete sign-in.`));
          setFlow(undefined);
        }
      }
    };
    timeout = setTimeout(checkAuthorization, flow.interval * 1000);
    return () => {
      stopped = true;
      clearTimeout(timeout);
    };
  }, [flow, credentialId, poll, providerType.type]);

  const handleStart = async () => {
    setError(undefined);
    setCredentialId(undefined);
    setFlow(undefined);
    try {
      const next = await start({ type: providerType.type }).unwrap();
      if (!isMounted.current) {
        void cancel({ type: providerType.type, flow_id: next.flow_id });
        return;
      }
      pendingFlow.current = next.flow_id;
      setFlow(next);
    } catch (caught) {
      setError(getErrorMessage(caught, t`Unable to start sign-in.`));
    }
  };

  const handleSave = async () => {
    setError(undefined);
    const config = credentialId
      ? { "oauth-credential-id": credentialId }
      : connection?.config;
    try {
      const saved = connection
        ? await update({ key: connection.key, name, config }).unwrap()
        : await create({ type: providerType.type, name, config }).unwrap();
      onSaved(saved);
    } catch (caught) {
      setError(
        getErrorMessage(caught, t`Unable to connect this subscription.`),
      );
    }
  };

  return (
    <Stack gap="lg">
      <Text>
        {t`Sign in to use your subscription instead of a separately billed API key. Model access and usage limits depend on your subscription tier.`}
      </Text>
      <Text c="text-secondary">
        {t`This connection is shared by everyone using Metabot on this instance.`}
      </Text>
      <TextInput
        label={t`Display name`}
        value={name}
        onChange={(event) => setName(event.currentTarget.value)}
        disabled={isSaving}
      />
      {flow && !credentialId && (
        <Stack gap="sm">
          <Anchor
            href={flow.verification_url}
            target="_blank"
            rel="noopener noreferrer"
          >
            {t`Open the sign-in page`}
          </Anchor>
          <Text>{t`Enter this code: ${flow.user_code}`}</Text>
          <Text c="text-secondary">{t`Waiting for authorization…`}</Text>
        </Stack>
      )}
      {credentialId && (
        <Text>{t`Signed in. Connect to verify model access and save this subscription.`}</Text>
      )}
      {error && <Text c="error">{error}</Text>}
      <Group justify="end">
        {onCancel && (
          <Button onClick={onCancel} disabled={isSaving}>
            {connection ? t`Cancel` : t`Back`}
          </Button>
        )}
        <Button
          onClick={handleStart}
          loading={startResult.isLoading}
          disabled={isSaving}
        >
          {flow || connection || credentialId ? t`Sign in again` : t`Sign in`}
        </Button>
        <Button
          variant="filled"
          onClick={handleSave}
          loading={isSaving}
          disabled={isSaving || (!credentialId && (!connection || !!flow))}
        >
          {connection ? t`Save` : t`Connect`}
        </Button>
      </Group>
    </Stack>
  );
}
