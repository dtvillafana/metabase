import type {
  CreateLlmProviderRequest,
  ExtractSourcesRequest,
  ExtractSourcesResponse,
  LlmConnectionModels,
  LlmOAuthPollRequest,
  LlmOAuthPollResponse,
  LlmOAuthStartRequest,
  LlmOAuthStartResponse,
  LlmProviderConnection,
  LlmProviderType,
  UpdateLlmProviderRequest,
} from "metabase-types/api";

import { Api } from "./api";
import { invalidateTags, listTag } from "./tags";

export const llmApi = Api.injectEndpoints({
  endpoints: (builder) => ({
    extractSources: builder.query<
      ExtractSourcesResponse,
      ExtractSourcesRequest
    >({
      query: (body) => ({
        method: "POST",
        url: "/api/llm/extract-sources",
        body,
      }),
    }),
    listLlmProviderTypes: builder.query<LlmProviderType[], void>({
      query: () => ({
        method: "GET",
        url: "/api/llm/provider-types",
      }),
    }),
    listLlmProviders: builder.query<LlmProviderConnection[], void>({
      query: () => ({
        method: "GET",
        url: "/api/llm/providers",
      }),
      providesTags: () => [listTag("llm-providers")],
    }),
    listLlmModels: builder.query<LlmConnectionModels[], void>({
      query: () => ({
        method: "GET",
        url: "/api/llm/models",
      }),
      providesTags: () => [listTag("llm-models")],
    }),
    createLlmProvider: builder.mutation<
      LlmProviderConnection,
      CreateLlmProviderRequest
    >({
      query: (body) => ({
        method: "POST",
        url: "/api/llm/providers",
        body,
      }),
      invalidatesTags: (_, error) =>
        invalidateTags(error, [
          listTag("llm-providers"),
          listTag("llm-models"),
          "session-properties",
        ]),
    }),
    startLlmOAuth: builder.mutation<
      LlmOAuthStartResponse,
      LlmOAuthStartRequest
    >({
      query: ({ type }) => ({
        method: "POST",
        url: `/api/llm/providers/oauth/${encodeURIComponent(type)}`,
      }),
    }),
    pollLlmOAuth: builder.mutation<LlmOAuthPollResponse, LlmOAuthPollRequest>({
      query: ({ type, flow_id }) => ({
        method: "POST",
        url: `/api/llm/providers/oauth/${encodeURIComponent(type)}/poll`,
        body: { flow_id },
      }),
    }),
    cancelLlmOAuth: builder.mutation<void, LlmOAuthPollRequest>({
      query: ({ type, flow_id }) => ({
        method: "DELETE",
        url: `/api/llm/providers/oauth/${encodeURIComponent(type)}/${encodeURIComponent(flow_id)}`,
      }),
    }),
    updateLlmProvider: builder.mutation<
      LlmProviderConnection,
      UpdateLlmProviderRequest
    >({
      query: ({ key, ...body }) => ({
        method: "PUT",
        url: `/api/llm/providers/${encodeURIComponent(key)}`,
        body,
      }),
      invalidatesTags: (_, error) =>
        invalidateTags(error, [
          listTag("llm-providers"),
          listTag("llm-models"),
          "session-properties",
        ]),
    }),
    deleteLlmProvider: builder.mutation<void, string>({
      query: (key) => ({
        method: "DELETE",
        url: `/api/llm/providers/${encodeURIComponent(key)}`,
      }),
      invalidatesTags: (_, error) =>
        invalidateTags(error, [
          listTag("llm-providers"),
          listTag("llm-models"),
          "session-properties",
        ]),
    }),
  }),
});

export const {
  useExtractSourcesQuery,
  useListLlmProviderTypesQuery,
  useListLlmProvidersQuery,
  useListLlmModelsQuery,
  useCreateLlmProviderMutation,
  useUpdateLlmProviderMutation,
  useDeleteLlmProviderMutation,
  useStartLlmOAuthMutation,
  usePollLlmOAuthMutation,
  useCancelLlmOAuthMutation,
} = llmApi;
