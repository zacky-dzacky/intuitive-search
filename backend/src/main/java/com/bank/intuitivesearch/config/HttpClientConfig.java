package com.bank.intuitivesearch.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration
@EnableScheduling
public class HttpClientConfig {

    /**
     * Query-embedding client, on the hot path. Timeouts are aggressive on
     * purpose: this call sits inside the search-as-you-type budget, so it is
     * better to drop the vector channel than to blow the budget.
     *
     * <p>Points at the OpenAI-compatible provider named in
     * {@code search.embedding.openai}, which defaults to the Stage-2 endpoint
     * and key.
     */
    @Bean
    RestClient embeddingRestClient(SearchProperties properties, ObjectMapper objectMapper) {
        return embeddingClient(properties, Duration.ofMillis(properties.getEmbedding().getTimeoutMs()),
                objectMapper);
    }

    /**
     * Document-embedding client, used by the index builder. Same endpoint,
     * relaxed deadline — the catalogue goes over in one batch, and a few
     * seconds at startup is not a few seconds per keystroke.
     */
    @Bean
    RestClient documentEmbeddingRestClient(SearchProperties properties, ObjectMapper objectMapper) {
        return embeddingClient(properties,
                Duration.ofMillis(properties.getEmbedding().getDocumentTimeoutMs()), objectMapper);
    }

    private static RestClient embeddingClient(SearchProperties properties, Duration readTimeout,
                                              ObjectMapper objectMapper) {
        return openAi(properties.getEmbedding().getOpenai(), readTimeout, objectMapper);
    }

    /** Local-LLM (Ollama / vLLM) client for Stage 2. */
    @Bean
    RestClient llmRestClient(SearchProperties properties, ObjectMapper objectMapper) {
        return builder(properties.getLlm().getOllama().getBaseUrl(),
                Duration.ofMillis(properties.getLlm().getTimeoutMs()),
                objectMapper).build();
    }

    /** OpenAI-compatible chat client for Stage 2 (Azure AI Foundry via the LiteLLM proxy). */
    @Bean
    RestClient openAiChatRestClient(SearchProperties properties, ObjectMapper objectMapper) {
        return openAi(properties.getLlm().getOpenai(),
                Duration.ofMillis(properties.getLlm().getTimeoutMs()), objectMapper);
    }

    private static RestClient openAi(SearchProperties.OpenAi config, Duration readTimeout,
                                     ObjectMapper objectMapper) {
        RestClient.Builder builder = builder(config.getBaseUrl(), readTimeout, objectMapper);
        String key = config.getApiKey() == null ? "" : config.getApiKey();
        if ("api-key".equalsIgnoreCase(config.getAuthHeader())) {
            builder.defaultHeader("api-key", key);
        } else {
            builder.defaultHeader("Authorization", "Bearer " + key);
        }
        return builder.build();
    }

    private static RestClient.Builder builder(String baseUrl, Duration readTimeout,
                                              ObjectMapper objectMapper) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(500))
                .withReadTimeout(readTimeout);
        ClientHttpRequestFactory factory =
                ClientHttpRequestFactoryBuilder.detect().build(settings);

        // A trailing slash on the base URL would double up with the
        // per-call "/embeddings" and 404 on strict servers.
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        return RestClient.builder()
                .baseUrl(base)
                .requestFactory(factory)
                // Reuse Boot's ObjectMapper so unknown fields from the other
                // service don't blow up deserialization.
                .messageConverters(converters -> converters.stream()
                        .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                        .map(MappingJackson2HttpMessageConverter.class::cast)
                        .forEach(c -> c.setObjectMapper(objectMapper)));
    }

    /**
     * Used to fan the two Stage-1 retrieval channels out in parallel. Virtual
     * threads make this free — there is no pool to size and no risk of
     * starving the request threads.
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService searchExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
