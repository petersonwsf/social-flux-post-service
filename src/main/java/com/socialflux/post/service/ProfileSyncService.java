package com.socialflux.post.service;

import com.socialflux.events.EventEnvelope;
import com.socialflux.post.domain.AuthorRepository;
import com.socialflux.post.domain.AuthorSnapshot;
import com.socialflux.post.domain.FollowEdgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Mantém a cópia local dos perfis dos autores ({@code author_snapshot}) a partir dos eventos {@code profile.*}.
 * <ul>
 *   <li>criação/edição: upsert (nome, foto, conta privada); a visibilidade nova vale na próxima consulta;</li>
 *   <li>exclusão (D12): marca {@code DELETED} — o conteúdo some de todas as consultas na hora — apaga as arestas e, depois do
 *   commit, o {@link AccountPurger} apaga postagens, respostas dependentes, curtidas e mídia em lotes curtos.</li>
 * </ul>
 * Versões iguais ou mais antigas que a gravada são ignoradas (duplicado ou fora de ordem), assim como tipos desconhecidos.
 * Quem chama já garante a idempotência por id de mensagem (inbox).
 */
@Service
public class ProfileSyncService {

    public static final String PROFILE_CREATED = "profile.created.v1";
    public static final String PROFILE_UPDATED = "profile.updated.v1";
    public static final String PROFILE_PRIVACY_CHANGED = "profile.privacy-changed.v1";
    public static final String PROFILE_DELETED = "profile.deleted.v1";

    private static final Logger log = LoggerFactory.getLogger(ProfileSyncService.class);

    private final AuthorRepository authors;
    private final FollowEdgeRepository edges;
    private final AccountPurger purger;
    private final Clock clock;

    public ProfileSyncService(AuthorRepository authors, FollowEdgeRepository edges, AccountPurger purger, Clock clock) {
        this.authors = authors;
        this.edges = edges;
        this.purger = purger;
        this.clock = clock;
    }

    @Transactional
    public void apply(EventEnvelope event) {
        switch (event.type()) {
            case PROFILE_CREATED, PROFILE_UPDATED -> upsert(event);
            case PROFILE_PRIVACY_CHANGED -> changePrivacy(event);
            case PROFILE_DELETED -> delete(event);
            default -> log.debug("Evento ignorado: {}", event.type());
        }
    }

    private void upsert(EventEnvelope event) {
        UUID id = event.aggregateId();
        Optional<AuthorSnapshot> current = authors.findForUpdate(id);
        if (current.isPresent() && current.get().profileVersion() >= event.aggregateVersion()) {
            return;   // duplicado ou mais antigo que o já aplicado (inclui o "túmulo" de uma conta excluída)
        }
        JsonNode data = event.data();
        AuthorSnapshot next = new AuthorSnapshot(id, data.path("username").asString(),
                data.path("displayName").asString(), nullable(data.path("avatarKey")),
                data.path("isPrivate").asBoolean(), AuthorSnapshot.ACTIVE, event.aggregateVersion());
        if (current.isPresent()) {
            authors.update(next, now());
        } else {
            authors.insert(next, now());
        }
    }

    private void changePrivacy(EventEnvelope event) {
        Optional<AuthorSnapshot> current = authors.findForUpdate(event.aggregateId());
        // Mesma versão do profile.updated que acompanha este evento: só ignora se for estritamente mais antigo.
        if (current.isEmpty() || !current.get().isActive() || current.get().profileVersion() > event.aggregateVersion()) {
            return;
        }
        AuthorSnapshot before = current.get();
        boolean isPrivate = event.data().path("isPrivate").asBoolean();
        if (before.isPrivate() == isPrivate) {
            return;
        }
        authors.update(new AuthorSnapshot(before.userId(), before.username(), before.displayName(),
                before.avatarKey(), isPrivate, before.status(), event.aggregateVersion()), now());
    }

    private void delete(EventEnvelope event) {
        UUID id = event.aggregateId();
        Optional<AuthorSnapshot> current = authors.findForUpdate(id);
        if (current.isPresent() && !current.get().isActive()) {
            return;   // já excluído
        }
        long version = Math.max(event.aggregateVersion(), current.map(AuthorSnapshot::profileVersion).orElse(0L));
        if (current.isPresent()) {
            AuthorSnapshot s = current.get();
            authors.update(new AuthorSnapshot(id, s.username(), s.displayName(), null, s.isPrivate(),
                    AuthorSnapshot.DELETED, version), now());
        } else {
            // Chegou antes do profile.created: guarda um "túmulo" para que um created atrasado (versão menor) não reviva a conta.
            authors.insert(new AuthorSnapshot(id, "excluido-" + id, "", null, false, AuthorSnapshot.DELETED, version),
                    now());
        }
        edges.deleteAllOf(id);
        purgeAfterCommit(id);
    }

    private void purgeAfterCommit(UUID id) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;   // a rotina agendada recolhe
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    purger.purgeAccount(id);
                } catch (RuntimeException e) {
                    log.warn("Limpeza da conta {} falhou (a rotina agendada tenta de novo): {}", id, e.toString());
                }
            }
        });
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String nullable(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
