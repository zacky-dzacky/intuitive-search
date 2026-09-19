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
     * Embedding service client. Timeouts are aggressive on purpose: this call
     * sits inside the &lt;100ms search-as-you-type budget, so it is better to
     * drop the vector channel than to blow the budget.
     */
    @Bean
    RestClient embeddingRestClient(SearchProperties properties, ObjectMapper objectMapper) {
        return build(properties.getEmbedding().getBaseUrl(),
                Duration.ofMillis(properties.getEmbedding().getTimeoutMs()),
                objectMapper);
    }

    /** Local-LLM (Ollama / vLLM) client for Stage 2. */
    @Bean
    RestClient llmRestClient(SearchProperties properties, ObjectMapper objectMapper) {
        return build(properties.getLlm().getOllama().getBaseUrl(),
                Duration.ofMillis(properties.getLlm().getTimeoutMs()),
                objectMapper);
    }

    private static RestClient build(String baseUrl, Duration readTimeout, ObjectMapper objectMapper) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(500))
                .withReadTimeout(readTimeout);
        ClientHttpRequestFactory factory =
                ClientHttpRequestFactoryBuilder.detect().build(settings);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                // Reuse Boot's ObjectMapper so unknown fields from the other
                // service don't blow up deserialization.
                .messageConverters(converters -> converters.stream()
                        .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                        .map(MappingJackson2HttpMessageConverter.class::cast)
                        .forEach(c -> c.setObjectMapper(objectMapper)))
                .build();
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
