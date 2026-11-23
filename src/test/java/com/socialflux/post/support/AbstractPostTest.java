package com.socialflux.post.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.socialflux.events.OutboxRelay;
import com.socialflux.media.storage.InMemoryMediaStorage;
import com.socialflux.media.storage.MediaStorage;
import com.socialflux.post.events.EventHandlers;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base dos testes de integração: sobe o serviço completo em porta aleatória contra um PostgreSQL REAL embutido (sem Docker),
 * um servidor JWKS local com uma chave RSA de teste (tokens validados como em produção) e armazenamento de mídia em memória.
 * Usuários e arestas de seguir nascem como no sistema real: por eventos {@code profile.*} e {@code follow.*} entregues aos
 * mesmos tratadores que o RabbitMQ usa.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "socialflux.events.enabled=false",
                "socialflux.media.storage=memory",
                "post.security.issuer=http://auth.test",
                "post.maintenance.enabled=false",
                "post.reconciler.enabled=false",
                "spring.flyway.enabled=true"
        })
@Import(TestSupportConfig.class)
public abstract class AbstractPostTest {

    protected static final String ISSUER = "http://auth.test";
    protected static final String USER_AUDIENCE = "socialflux-api";
    protected static final String BUCKET = "post-media";

    protected static final EmbeddedPostgres POSTGRES;
    protected static final RSAKey KEY;
    private static final HttpServer JWKS;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            JWKS.createContext("/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            JWKS.start();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao preparar a infraestrutura de teste", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("post.security.jwks-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks.json");
    }

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected EventHandlers events;

    @Autowired
    protected OutboxRelay relay;

    @Autowired
    protected TestSupportConfig.RecordingPublisher publisher;

    @Autowired
    private MediaStorage mediaStorage;

    private Http http;

    protected Http http() {
        if (http == null) {
            http = new Http(port);
        }
        return http;
    }

    protected InMemoryMediaStorage storage() {
        return (InMemoryMediaStorage) mediaStorage;
    }

    // ------------------------------------------------------------------ tokens

    protected static String userToken(UUID accountId) {
        return sign(new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(USER_AUDIENCE).subject(accountId.toString())
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
                .claim("roles", List.of("USER")).claim("ver", 1).build());
    }

    protected static String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ usuários e arestas (por eventos)

    /** Um usuário de teste. {@code version} é o {@code aggregateVersion} do último evento de perfil enviado. */
    protected static final class TestUser {
        public final UUID id = UUID.randomUUID();
        public final String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        public final String token = userToken(id);
        public final Map<UUID, UUID> relations = new HashMap<>();   // seguido -> relationId
        public long version = 0;
        public boolean isPrivate;
    }

    /** Usuário com token válido cujo perfil o serviço ainda não conhece. */
    protected TestUser unknownUser() {
        return new TestUser();
    }

    protected TestUser newUser() {
        return newUser(false);
    }

    protected TestUser newUser(boolean isPrivate) {
        TestUser user = new TestUser();
        user.isPrivate = isPrivate;
        user.version = 1;
        deliverProfile("profile.created.v1", user);
        return user;
    }

    protected void setPrivate(TestUser user, boolean isPrivate) {
        user.isPrivate = isPrivate;
        user.version++;
        deliverProfile("profile.updated.v1", user);
        deliverProfile("profile.privacy-changed.v1", user);
    }

    protected void deleteProfile(TestUser user) {
        user.version++;
        deliverProfile("profile.deleted.v1", user);
    }

    protected void deliverProfile(String type, TestUser user) {
        events.handleProfile(profileEventJson(UUID.randomUUID(), type, user));
    }

    protected static String profileEventJson(UUID messageId, String type, TestUser user) {
        return """
                {"id":"%s","type":"%s","occurredAt":"2026-01-01T00:00:00Z","aggregateId":"%s","aggregateVersion":%d,
                 "data":{"accountId":"%s","username":"%s","displayName":"Pessoa %s","bio":null,
                         "avatarKey":null,"isPrivate":%s,"createdAt":"2026-01-01T00:00:00Z"}}
                """.formatted(messageId, type, user.id, user.version, user.id, user.username,
                user.username, user.isPrivate);
    }

    /** {@code follower} passa a seguir {@code followee} (aresta aceita, como o grafo publica). */
    protected UUID follow(TestUser follower, TestUser followee) {
        UUID relationId = UUID.randomUUID();
        follower.relations.put(followee.id, relationId);
        events.handleFollow(followEventJson(UUID.randomUUID(), "follow.created.v1", relationId, follower.id, followee.id,
                Instant.now()));
        return relationId;
    }

    protected void unfollow(TestUser follower, TestUser followee) {
        UUID relationId = follower.relations.remove(followee.id);
        events.handleFollow(followEventJson(UUID.randomUUID(), "follow.removed.v1", relationId, follower.id, followee.id,
                null));
    }

    protected static String followEventJson(UUID messageId, String type, UUID relationId, UUID follower, UUID followee,
                                            Instant createdAt) {
        String extra = createdAt == null ? "\"reason\":\"UNFOLLOW\"" : "\"origin\":\"DIRECT\",\"createdAt\":\"" + createdAt + "\"";
        return """
                {"id":"%s","type":"%s","occurredAt":"2026-01-01T00:00:00Z","aggregateId":"%s","aggregateVersion":1,
                 "data":{"relationId":"%s","followerId":"%s","followeeId":"%s",%s}}
                """.formatted(messageId, type, relationId, relationId, follower, followee, extra);
    }

    // ------------------------------------------------------------------ atalhos de chamadas

    protected Http.Response createPost(TestUser author, String text) {
        return createPost(author, text, null, null);
    }

    protected Http.Response createPost(TestUser author, String text, String parentId, byte[] image) {
        Map<String, String> fields = new HashMap<>();
        if (text != null) {
            fields.put("legenda", text);
        }
        if (parentId != null) {
            fields.put("pai", parentId);
        }
        return http().multipart("POST", "/api/postagens", author.token, "imagem", "anexo.bin", "image/png", image, fields);
    }

    /** Cria e devolve o id (falha o teste se não for 201). */
    protected String post(TestUser author, String text) {
        Http.Response response = createPost(author, text);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return (String) response.json().get("id");
    }

    protected String reply(TestUser author, String parentId, String text) {
        Http.Response response = createPost(author, text, parentId, null);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return (String) response.json().get("id");
    }

    protected Http.Response get(TestUser viewer, String path) {
        return http().get(path, viewer.token);
    }

    protected Http.Response like(TestUser viewer, String postId) {
        return http().send("PUT", "/api/postagens/" + postId + "/curtida", null, viewer.token);
    }

    protected Http.Response unlike(TestUser viewer, String postId) {
        return http().send("DELETE", "/api/postagens/" + postId + "/curtida", null, viewer.token);
    }

    protected Http.Response delete(TestUser viewer, String postId) {
        return http().send("DELETE", "/api/postagens/" + postId, null, viewer.token);
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> items(Http.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return (List<Map<String, Object>>) response.json().get("itens");
    }

    protected static List<Object> ids(Http.Response response) {
        return items(response).stream().map(item -> item.get("id")).toList();
    }

    // ------------------------------------------------------------------ imagens

    protected static byte[] png(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(30, 120, 200));
        g.fillRect(0, 0, width, height);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** GIF animado mínimo, válido, com {@code frames} quadros 1×1 numa tela {@code w}×{@code h} e laço infinito. */
    protected static byte[] gif(int w, int h, int frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'G', 'I', 'F', '8', '9', 'a', (byte) w, (byte) (w >> 8), (byte) h, (byte) (h >> 8),
                (byte) 0x80, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0});
        out.writeBytes(new byte[]{0x21, (byte) 0xFF, 11});
        out.writeBytes("NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{3, 1, 0, 0, 0});
        for (int i = 0; i < frames; i++) {
            out.writeBytes(new byte[]{0x21, (byte) 0xF9, 4, 0, 10, 0, 0, 0});
            out.writeBytes(new byte[]{0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0});
        }
        out.write(0x3B);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ eventos / banco

    protected void drainOutbox() {
        for (int i = 0; i < 100 && relay.publishPending() > 0; i++) {
            // continua até esvaziar
        }
    }

    /** Tipos de evento gravados no outbox cujo payload cita o id (ordem de gravação). */
    protected List<String> eventTypesMentioning(Object id) {
        return jdbc.sql("select type from outbox_event where payload like :like order by seq")
                .param("like", "%" + id + "%").query(String.class).list();
    }

    /** Os contadores desnormalizados batem com as linhas reais. */
    protected void assertCountersConsistent() {
        long divergent = jdbc.sql("""
                        select count(*) from post p
                        where p.likes_count <> (select count(*) from post_like l where l.post_id = p.id)
                           or p.replies_count <> (select count(*) from post r where r.parent_id = p.id)""")
                .query(Long.class).single();
        assertThat(divergent).as("postagens com contadores divergentes").isZero();
    }

    protected long count(String sql, Object id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }
}
