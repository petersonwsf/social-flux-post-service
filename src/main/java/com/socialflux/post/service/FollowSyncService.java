package com.socialflux.post.service;

import com.socialflux.events.EventEnvelope;
import com.socialflux.post.domain.FollowEdgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Mantém a cópia local das arestas de seguir (D5) a partir de {@code follow.created.v1} e {@code follow.removed.v1}. Os
 * eventos podem chegar fora de ordem: cada vínculo tem um {@code relationId} próprio, um {@code removed} deixa um túmulo que
 * barra o {@code created} atrasado do mesmo vínculo, e um {@code removed} só apaga a aresta se ela for daquele vínculo.
 * Os demais {@code follow.*} (solicitações) não mudam quem vê o quê e são ignorados.
 */
@Service
public class FollowSyncService {

    public static final String FOLLOW_CREATED = "follow.created.v1";
    public static final String FOLLOW_REMOVED = "follow.removed.v1";

    private static final Logger log = LoggerFactory.getLogger(FollowSyncService.class);

    private final FollowEdgeRepository edges;
    private final Clock clock;

    public FollowSyncService(FollowEdgeRepository edges, Clock clock) {
        this.edges = edges;
        this.clock = clock;
    }

    @Transactional
    public void apply(EventEnvelope event) {
        switch (event.type()) {
            case FOLLOW_CREATED -> created(event);
            case FOLLOW_REMOVED -> removed(event);
            default -> log.debug("Evento ignorado: {}", event.type());
        }
    }

    private void created(EventEnvelope event) {
        JsonNode data = event.data();
        UUID relationId = uuid(data, "relationId", event.aggregateId());
        if (edges.isRemoved(relationId)) {
            return;   // o vínculo já foi desfeito: o created chegou atrasado
        }
        OffsetDateTime createdAt = data.path("createdAt").isMissingNode() || data.path("createdAt").isNull()
                ? now()
                : OffsetDateTime.ofInstant(Instant.parse(data.path("createdAt").asString()), ZoneOffset.UTC);
        edges.upsert(relationId, uuid(data, "followerId", null), uuid(data, "followeeId", null), createdAt);
    }

    private void removed(EventEnvelope event) {
        JsonNode data = event.data();
        UUID relationId = uuid(data, "relationId", event.aggregateId());
        edges.deleteIfRelation(relationId, uuid(data, "followerId", null), uuid(data, "followeeId", null));
        edges.markRemoved(relationId, now());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static UUID uuid(JsonNode data, String field, UUID fallback) {
        JsonNode node = data.path(field);
        if (node.isMissingNode() || node.isNull()) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("Evento de relação sem o campo obrigatório '" + field + "'");
        }
        return UUID.fromString(node.asString());
    }
}
