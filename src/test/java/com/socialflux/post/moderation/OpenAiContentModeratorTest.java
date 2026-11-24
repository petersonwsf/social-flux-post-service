package com.socialflux.post.moderation;

import com.socialflux.post.config.PostProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cliente da API de moderação contra um servidor HTTP local que imita {@code /v1/moderations}. */
class OpenAiContentModeratorTest {

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "{}";
    private volatile long delayMillis;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/moderations", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenAiContentModerator moderator(Duration timeout) {
        PostProperties.Thresholds thresholds = new PostProperties.Thresholds(0.8, 0.8, 0.5, 0.7);
        PostProperties.Moderation properties = new PostProperties.Moderation(true, "sk-teste",
                "http://127.0.0.1:" + server.getAddress().getPort(), "omni-moderation-latest", timeout, false, thresholds);
        return new OpenAiContentModerator(properties, new ModerationPolicy(thresholds));
    }

    private static String scores(String categoryScores) {
        return "{\"id\":\"modr-1\",\"model\":\"omni-moderation-latest\",\"results\":[{\"flagged\":true,"
                + "\"categories\":{},\"category_scores\":" + categoryScores + "}]}";
    }

    @Test
    void sendsModelInputAndKey() {
        responseBody = scores("{\"hate\":0.01}");
        moderator(Duration.ofSeconds(2)).checkText("texto de teste");
        assertThat(authorization.get()).isEqualTo("Bearer sk-teste");
        assertThat(requestBody.get()).contains("\"model\":\"omni-moderation-latest\"").contains("\"input\":\"texto de teste\"");
    }

    @Test
    void lowScoresAreAllowed() {
        responseBody = scores("{\"hate\":0.02,\"harassment\":0.97,\"violence\":0.9,\"self-harm\":0.0}");
        assertThat(moderator(Duration.ofSeconds(2)).checkText("ofensa comum").allowed()).isTrue();
    }

    @Test
    void highHateScoreIsForbidden() {
        responseBody = scores("{\"hate\":0.93,\"hate/threatening\":0.1}");
        Verdict verdict = moderator(Duration.ofSeconds(2)).checkText("x");
        assertThat(verdict.kind()).isEqualTo(Verdict.Kind.FORBIDDEN);
        assertThat(verdict.category()).isEqualTo("hate");
    }

    @Test
    void selfHarmIntentScoreIsSelfHarm() {
        responseBody = scores("{\"self-harm/intent\":0.88}");
        assertThat(moderator(Duration.ofSeconds(2)).checkText("x").kind()).isEqualTo(Verdict.Kind.SELF_HARM);
    }

    @Test
    void graphicViolenceScoreIsForbidden() {
        responseBody = scores("{\"violence/graphic\":0.71}");
        assertThat(moderator(Duration.ofSeconds(2)).checkText("x").kind()).isEqualTo(Verdict.Kind.FORBIDDEN);
    }

    @Test
    void httpErrorIsUnavailableWithoutTheText() {
        status = 500;
        assertThatThrownBy(() -> moderator(Duration.ofSeconds(2)).checkText("conteúdo secreto"))
                .isInstanceOf(ModerationUnavailableException.class)
                .hasMessageContaining("500")
                .hasMessageNotContaining("secreto");
    }

    @Test
    void unauthorizedIsUnavailable() {
        status = 401;
        assertThatThrownBy(() -> moderator(Duration.ofSeconds(2)).checkText("x"))
                .isInstanceOf(ModerationUnavailableException.class);
    }

    @Test
    void unreadableResponseIsUnavailable() {
        responseBody = "isto não é json";
        assertThatThrownBy(() -> moderator(Duration.ofSeconds(2)).checkText("x"))
                .isInstanceOf(ModerationUnavailableException.class);
    }

    @Test
    void responseWithoutScoresIsUnavailable() {
        responseBody = "{\"results\":[]}";
        assertThatThrownBy(() -> moderator(Duration.ofSeconds(2)).checkText("x"))
                .isInstanceOf(ModerationUnavailableException.class);
    }

    @Test
    void slowProviderTimesOut() {
        responseBody = scores("{\"hate\":0.0}");
        delayMillis = 1500;
        assertThatThrownBy(() -> moderator(Duration.ofMillis(300)).checkText("x"))
                .isInstanceOf(ModerationUnavailableException.class);
    }
}
