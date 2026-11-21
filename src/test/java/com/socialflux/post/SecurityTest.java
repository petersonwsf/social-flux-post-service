package com.socialflux.post;

import com.nimbusds.jwt.JWTClaimsSet;
import com.socialflux.post.support.AbstractPostTest;
import com.socialflux.post.support.Http;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Resource server: só token de usuário válido (RS256 via JWKS, issuer, audiência, validade) e defesa contra CSRF. */
class SecurityTest extends AbstractPostTest {

    @Test
    void everyRouteRequiresAValidUserToken() {
        TestUser ana = newUser();
        String id = post(ana, "olá");
        for (String path : List.of("/api/postagens/feed", "/api/postagens/" + id, "/api/postagens?autor=" + ana.id,
                "/api/postagens/" + id + "/respostas", "/api/postagens/" + id + "/ancestrais", "/media/posts/po_x.jpg")) {
            Http.Response response = http().get(path, null);
            assertThat(response.status()).as(path).isEqualTo(401);
            assertThat(response.json()).containsEntry("codigo", "unauthorized");
        }
        assertThat(http().send("PUT", "/api/postagens/" + id + "/curtida", null, null).status()).isEqualTo(401);
        assertThat(http().send("DELETE", "/api/postagens/" + id, null, null).status()).isEqualTo(401);
    }

    @Test
    void tamperedExpiredWrongAudienceAndServiceTokensAreRefused() {
        TestUser ana = newUser();
        String[] parts = ana.token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();
        assertThat(http().get("/api/postagens/feed", tampered).status()).isEqualTo(401);

        String wrongAudience = sign(new JWTClaimsSet.Builder().issuer(ISSUER).audience("outro").subject(ana.id.toString())
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .claim("roles", List.of("USER")).build());
        assertThat(http().get("/api/postagens/feed", wrongAudience).status()).isEqualTo(401);

        String expired = sign(new JWTClaimsSet.Builder().issuer(ISSUER).audience(USER_AUDIENCE).subject(ana.id.toString())
                .issueTime(new Date(System.currentTimeMillis() - 7_200_000))
                .expirationTime(new Date(System.currentTimeMillis() - 3_600_000))
                .claim("roles", List.of("USER")).build());
        assertThat(http().get("/api/postagens/feed", expired).status()).isEqualTo(401);

        String service = sign(new JWTClaimsSet.Builder().issuer(ISSUER).audience(USER_AUDIENCE).subject("svc:outro")
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .claim("roles", List.of("USER")).build());
        assertThat(http().get("/api/postagens/feed", service).status()).isEqualTo(403);
    }

    @Test
    void crossSiteStateChangesAreRefused() {
        TestUser ana = newUser();
        String id = post(ana, "olá");
        Http.Response response = http().send("PUT", "/api/postagens/" + id + "/curtida", null, ana.token,
                Map.of("Sec-Fetch-Site", "cross-site"));
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json()).containsEntry("codigo", "origem_invalida");
        Http.Response evil = http().send("DELETE", "/api/postagens/" + id, null, ana.token, Map.of("Origin", "https://mal.example"));
        assertThat(evil.status()).isEqualTo(403);
        assertThat(get(ana, "/api/postagens/" + id).status()).isEqualTo(200);
    }

    @Test
    void healthIsPublicAndUnknownRoutesAreDenied() {
        assertThat(http().get("/actuator/health", null).status()).isEqualTo(200);
        assertThat(http().get("/internal/qualquer", userToken(UUID.randomUUID())).status()).isEqualTo(403);
    }
}
