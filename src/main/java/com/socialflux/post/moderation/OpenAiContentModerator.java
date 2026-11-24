package com.socialflux.post.moderation;

import com.socialflux.post.config.PostProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Moderador de texto pela API de moderação da OpenAI ({@code POST /v1/moderations}). Só lê os scores das categorias que a
 * {@link ModerationPolicy} usa. Qualquer falha vira {@link ModerationUnavailableException} sem o conteúdo na mensagem.
 */
public class OpenAiContentModerator implements ContentModerator {

    private static final String[] TEXT_CATEGORIES = {
            ModerationPolicy.HATE, ModerationPolicy.HATE_THREATENING, ModerationPolicy.HARASSMENT_THREATENING,
            ModerationPolicy.SELF_HARM,
            ModerationPolicy.SELF_HARM_INTENT, ModerationPolicy.SELF_HARM_INSTRUCTIONS, ModerationPolicy.VIOLENCE_GRAPHIC};

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient client;
    private final String model;
    private final ModerationPolicy policy;

    public OpenAiContentModerator(PostProperties.Moderation properties, ModerationPolicy policy) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
        factory.setReadTimeout(properties.timeout());
        this.client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
        this.model = properties.model();
        this.policy = policy;
    }

    @Override
    public Verdict checkText(String text) {
        try {
            String request = MAPPER.writeValueAsString(Map.of("model", model, "input", text));
            String response = client.post()
                    .uri("/v1/moderations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return policy.evaluateText(scoresOf(response));
        } catch (RestClientResponseException e) {
            throw new ModerationUnavailableException("O provedor de moderação respondeu HTTP " + e.getStatusCode().value(), e);
        } catch (RuntimeException e) {
            throw new ModerationUnavailableException("Falha ao consultar o provedor de moderação: " + e.getClass().getSimpleName(), e);
        }
    }

    private static Map<String, Double> scoresOf(String response) {
        JsonNode scores = MAPPER.readTree(response).path("results").path(0).path("category_scores");
        if (!scores.isObject()) {
            throw new IllegalStateException("Resposta sem category_scores");
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (String category : TEXT_CATEGORIES) {
            result.put(category, scores.path(category).asDouble(0.0));
        }
        return result;
    }
}
