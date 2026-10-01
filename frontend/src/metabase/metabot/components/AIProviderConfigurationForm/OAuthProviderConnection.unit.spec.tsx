import userEvent from "@testing-library/user-event";
import fetchMock from "fetch-mock";

import { act, renderWithProviders, screen, waitFor } from "__support__/ui";
import type { LlmProviderConnection } from "metabase-types/api";
import {
  createMockLlmProviderConnection,
  createMockLlmProviderType,
} from "metabase-types/api/mocks";

import { ProviderConnectionForm } from "./ProviderConnectionForm";

const providerType = createMockLlmProviderType({
  type: "chatgpt",
  label: "ChatGPT subscription",
  oauth: true,
  fields: [],
});

const flow = {
  flow_id: "flow-id",
  verification_url: "https://auth.openai.com/codex/device",
  user_code: "ABCD-EFGH",
  expires_in: 600,
  interval: 1,
};

function setup(connection?: LlmProviderConnection) {
  fetchMock.post("path:/api/llm/providers/oauth/chatgpt", flow);
  fetchMock.post(
    "path:/api/llm/providers/oauth/chatgpt/poll",
    {
      status: "authorized",
      credential_id: "credential-id",
    },
    { name: "poll-oauth" },
  );
  fetchMock.delete("path:/api/llm/providers/oauth/chatgpt/flow-id", 204);
  const saved = createMockLlmProviderConnection({
    key: "chatgpt",
    type: "chatgpt",
    config: { "oauth-credential-id": "credential-id" },
  });
  fetchMock.post("path:/api/llm/providers", saved);
  fetchMock.put("path:/api/llm/providers/chatgpt", saved);
  const onSaved = jest.fn();
  const onCancel = jest.fn();
  const user = userEvent.setup({ advanceTimers: jest.advanceTimersByTime });
  const view = renderWithProviders(
    <ProviderConnectionForm
      providerTypes={[providerType]}
      connection={connection}
      onSaved={onSaved}
      onCancel={onCancel}
    />,
  );
  return { ...view, user, onSaved, onCancel };
}

async function tick() {
  await act(async () => {
    await jest.advanceTimersByTimeAsync(1000);
  });
}

describe("OAuth provider connections", () => {
  beforeEach(() => {
    jest.useFakeTimers();
    fetchMock.removeRoutes();
    fetchMock.clearHistory();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it("shows device sign-in instead of API key inputs and saves only a credential reference", async () => {
    const { user, onSaved } = setup();
    await user.click(
      screen.getByRole("button", { name: /ChatGPT subscription/ }),
    );
    expect(screen.queryByLabelText(/API key/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Connect" })).toBeDisabled();
    expect(screen.getByText(/shared by everyone/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByText(/ABCD-EFGH/)).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Open the sign-in page" }),
    ).toHaveAttribute("href", flow.verification_url);
    await tick();
    expect(await screen.findByText(/Signed in\./)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Connect" }));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(
      await fetchMock.callHistory
        .lastCall("path:/api/llm/providers")
        ?.request?.json(),
    ).toEqual({
      type: "chatgpt",
      name: "ChatGPT subscription",
      config: { "oauth-credential-id": "credential-id" },
    });
  });

  it("keeps polling pending authorization without enabling Connect", async () => {
    const { user } = setup();
    fetchMock.modifyRoute("poll-oauth", {
      response: { status: 200, body: { status: "pending" } },
    });
    await user.click(
      screen.getByRole("button", { name: /ChatGPT subscription/ }),
    );
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    await screen.findByText(/ABCD-EFGH/);
    await tick();
    expect(screen.getByRole("button", { name: "Connect" })).toBeDisabled();
    await tick();
    expect(
      fetchMock.callHistory.calls("path:/api/llm/providers/oauth/chatgpt/poll"),
    ).toHaveLength(2);
  });

  it("shows authorization errors and lets the administrator retry", async () => {
    const { user } = setup();
    fetchMock.modifyRoute("poll-oauth", {
      response: { status: 400, body: { message: "Sign-in denied" } },
    });
    await user.click(
      screen.getByRole("button", { name: /ChatGPT subscription/ }),
    );
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    await screen.findByText(/ABCD-EFGH/);
    await tick();
    expect(await screen.findByText("Sign-in denied")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Connect" })).toBeDisabled();
  });

  it("cancels pending authorization and stops polling when the form closes", async () => {
    const { user, unmount } = setup();
    await user.click(
      screen.getByRole("button", { name: /ChatGPT subscription/ }),
    );
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    await screen.findByText(/ABCD-EFGH/);
    unmount();
    await tick();
    expect(
      fetchMock.callHistory.calls(
        "path:/api/llm/providers/oauth/chatgpt/flow-id",
      ),
    ).toHaveLength(1);
    expect(
      fetchMock.callHistory.calls("path:/api/llm/providers/oauth/chatgpt/poll"),
    ).toHaveLength(0);
  });

  it("allows renaming an existing connection without signing in again", async () => {
    const connection = createMockLlmProviderConnection({
      key: "chatgpt",
      type: "chatgpt",
      config: { "oauth-credential-id": "existing-credential" },
    });
    const { user, onSaved } = setup(connection);
    await user.clear(screen.getByLabelText("Display name"));
    await user.type(screen.getByLabelText("Display name"), "Work subscription");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(
      await fetchMock.callHistory
        .lastCall("path:/api/llm/providers/chatgpt")
        ?.request?.json(),
    ).toEqual({
      name: "Work subscription",
      config: { "oauth-credential-id": "existing-credential" },
    });
  });
});
