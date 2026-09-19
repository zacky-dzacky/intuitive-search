package com.bank.intuitivesearch.embedding;

import com.bank.intuitivesearch.config.SearchProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Any provider that speaks the OpenAI {@code /embeddings} wire format.
 *
 * <p>That is Gemini today
 * ({@code https://generativelanguage.googleapis.com/v1beta/openai}) and Azure
 * AI Foundry when the sandbox lands
 * ({@code https://<resource>.openai.azure.com/openai/v1}). The request is
 * identical; only base URL, key and model name differ, and those are
 * configuration. There is deliberately no provider SDK here — the wire
 * format is four fields, and an SDK would be the thing that makes the swap
 * a code change.
 *
 * <p>{@code dimensions} is sent for providers that honour it (OpenAI,
 * Azure); for those that ignore it the response is cut to size client-side,
 * see {@link Vectors#fit}. Either way the index only ever sees vectors of
 * the configured length.
 *
 * <p>This is the only embedding backend. With no endpoint or key configured
 * it reports itself unconfigured and the pipeline runs lexical-only — which
 * is the dev/CI mode; there is no self-hosted model to fall back to.
 */
@Component
public class OpenAiEmbeddingBackend implements EmbeddingBackend {

    private final RestClient restClient;
    private final RestClient documentRestClient;
    private final SearchProperties properties;

    public OpenAiEmbeddingBackend(RestClient embeddingRestClient,
                                  RestClient documentEmbeddingRestClient,
                                  SearchProperties properties) {
        this.restClient = embeddingRestClient;
        this.documentRestClient = documentEmbeddingRestClient;
        this.properties = properties;
    }

    @Override
    public float[] embedQuery(String text) {
        String prefix = properties.getEmbedding().getQueryPrefix();
        return call(restClient, List.of(prefix == null ? text : prefix + text)).get(0);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        String prefix = properties.getEmbedding().getDocumentPrefix();
        List<String> inputs = prefix == null || prefix.isEmpty()
                ? texts
                : texts.stream().map(t -> prefix + t).toList();
        return call(documentRestClient, inputs);
    }

    private List<float[]> call(RestClient client, List<String> inputs) {
        SearchProperties.Embedding config = properties.getEmbedding();
        EmbeddingsResponse response = client.post()
                .uri("/embeddings")
                .body(new EmbeddingsRequest(config.getOpenai().getModel(), inputs, config.getDimensions()))
                .retrieve()
                .body(EmbeddingsResponse.class);
        if (response == null || response.data() == null || response.data().size() != inputs.size()) {
            throw new IllegalStateException("embeddings response did not match input size");
        }
        // The spec orders `data` by `index`; sort defensively rather than
        // trust it, because a mis-ordered batch would silently swap vectors
        // between features.
        List<EmbeddingDatum> data = new ArrayList<>(response.data());
        data.sort(Comparator.comparingInt(d -> d.index() == null ? 0 : d.index()));
        List<float[]> out = new ArrayList<>(data.size());
        for (EmbeddingDatum datum : data) {
            if (datum.embedding() == null || datum.embedding().isEmpty()) {
                throw new IllegalStateException("empty embedding in response");
            }
            out.add(Vectors.fit(Vectors.toFloatArray(datum.embedding()), config.getDimensions()));
        }
        return out;
    }

    @Override
    public String describe() {
        SearchProperties.OpenAi openai = properties.getEmbedding().getOpenai();
        if (!openai.isConfigured()) {
            return "openai (not configured: set OPENAI_BASE_URL, OPENAI_API_KEY, OPENAI_EMBEDDING_MODEL)";
        }
        return openai.getModel() + " @ " + openai.getBaseUrl();
    }

    @Override
    public boolean isConfigured() {
        return properties.getEmbedding().getOpenai().isConfigured();
    }

    record EmbeddingsRequest(
            @JsonProperty("model") String model,
            @JsonProperty("input") List<String> input,
            @JsonProperty("dimensions") Integer dimensions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingsResponse(
            @JsonProperty("data") List<EmbeddingDatum> data,
            @JsonProperty("model") String model) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingDatum(
            @JsonProperty("index") Integer index,
            @JsonProperty("embedding") List<Double> embedding) {}
}
