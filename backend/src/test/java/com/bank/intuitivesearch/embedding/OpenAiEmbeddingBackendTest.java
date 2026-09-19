package com.bank.intuitivesearch.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.bank.intuitivesearch.config.SearchProperties;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The wire contract that makes Gemini today and Azure AI Foundry tomorrow the same adapter. */
class OpenAiEmbeddingBackendTest {

    private static final String BASE = "https://provider.example/v1";

    private MockRestServiceServer server;
    private OpenAiEmbeddingBackend backend;
    private SearchProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SearchProperties();
        properties.getEmbedding().setDimensions(3);
        properties.getEmbedding().getOpenai().setBaseUrl(BASE);
        properties.getEmbedding().getOpenai().setApiKey("k-123");
        properties.getEmbedding().getOpenai().setModel("text-embedding-3-small");

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE)
                .defaultHeader("Authorization", "Bearer k-123");
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        backend = new OpenAiEmbeddingBackend(client, client, properties);
    }

    @Test
    @DisplayName("a query goes to /embeddings with the model, the key and the requested size")
    void queryRequestShape() {
        server.expect(requestTo(BASE + "/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer k-123"))
                .andExpect(jsonPath("$.model").value("text-embedding-3-small"))
                .andExpect(jsonPath("$.input[0]").value("transfer money"))
                .andExpect(jsonPath("$.dimensions").value(3))
                .andRespond(withSuccess("""
                        {"data":[{"index":0,"embedding":[0.6,0.8,0.0]}],"model":"text-embedding-3-small"}
                        """, MediaType.APPLICATION_JSON));

        float[] vector = backend.embedQuery("transfer money");
        assertThat(vector).containsExactly(0.6f, 0.8f, 0.0f);
        server.verify();
    }

    @Test
    @DisplayName("a provider that ignores `dimensions` gets its 3072-dim answer cut to size")
    void truncatesOversizedResponse() {
        StringBuilder big = new StringBuilder("[1.0,0.0,0.0");
        for (int i = 3; i < 3072; i++) {
            big.append(",0.5");
        }
        big.append("]");
        server.expect(requestTo(BASE + "/embeddings"))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":" + big + "}]}",
                        MediaType.APPLICATION_JSON));

        float[] vector = backend.embedQuery("x");
        assertThat(vector).hasSize(3);
        assertThat(vector[0]).isCloseTo(1.0f, Offset.offset(1e-6f));
    }

    @Test
    @DisplayName("documents go in one batch and come back in index order, whatever order the wire used")
    void batchKeepsOrder() {
        server.expect(requestTo(BASE + "/embeddings"))
                .andExpect(jsonPath("$.input.length()").value(2))
                .andRespond(withSuccess("""
                        {"data":[{"index":1,"embedding":[0,1,0]},{"index":0,"embedding":[1,0,0]}]}
                        """, MediaType.APPLICATION_JSON));

        List<float[]> vectors = backend.embedDocuments(List.of("first", "second"));
        assertThat(vectors.get(0)).containsExactly(1, 0, 0);
        assertThat(vectors.get(1)).containsExactly(0, 1, 0);
    }

    @Test
    @DisplayName("the document prefix is applied to documents only")
    void documentPrefix() {
        properties.getEmbedding().setDocumentPrefix("passage: ");
        server.expect(requestTo(BASE + "/embeddings"))
                .andExpect(jsonPath("$.input[0]").value("passage: alpha"))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1,0,0]}]}",
                        MediaType.APPLICATION_JSON));
        backend.embedDocuments(List.of("alpha"));
        server.verify();
    }

    @Test
    @DisplayName("a short answer, a size mismatch, or an HTTP error all surface as exceptions for the client to contain")
    void failuresThrow() {
        server.expect(requestTo(BASE + "/embeddings"))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/embeddings"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/embeddings"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> backend.embedQuery("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> backend.embedQuery("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> backend.embedQuery("x")).isInstanceOf(Exception.class);
        server.verify();
    }

    @Test
    void describesModelAndHost() {
        assertThat(backend.describe()).isEqualTo("text-embedding-3-small @ " + BASE);
    }

    @Test
    @DisplayName("the request body carries no provider-specific fields — the whole point of the adapter")
    void bodyIsPlainOpenAi() {
        server.expect(requestTo(BASE + "/embeddings"))
                .andExpect(content().json("""
                        {"model":"text-embedding-3-small","input":["q"],"dimensions":3}
                        """, true))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1,0,0]}]}",
                        MediaType.APPLICATION_JSON));
        backend.embedQuery("q");
        server.verify();
    }
}
