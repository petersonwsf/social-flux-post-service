package com.socialflux.post.service;

import com.socialflux.post.config.PostProperties;
import com.socialflux.post.domain.AuthorRepository;
import com.socialflux.post.domain.FollowEdgeRepository;
import com.socialflux.events.logging.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Rotinas agendadas (E), a cada {@code post.maintenance.interval} (a primeira, 10 minutos depois de subir). Cada tarefa é
 * independente: a falha de uma só gera log e não impede as outras.
 * <ol>
 *   <li>poda os túmulos de vínculos removidos ({@code follow_removed}) mais velhos que {@code tombstone-retention};</li>
 *   <li>varre o bucket e apaga imagens órfãs (sem postagem) mais velhas que {@code post.media.orphan-grace};</li>
 *   <li>termina a limpeza de contas excluídas que ainda tenham conteúdo (se a limpeza logo após o evento falhou).</li>
 * </ol>
 * A limpeza do outbox já vem da biblioteca de eventos e o reconciliador de contadores tem rotina própria.
 */
@Component
public class Maintenance {

    private static final Logger log = LoggerFactory.getLogger(Maintenance.class);
    private static final int ACCOUNTS_PER_ROUND = 50;

    private final FollowEdgeRepository edges;
    private final AuthorRepository authors;
    private final PostMediaService media;
    private final AccountPurger purger;
    private final PostProperties.Maintenance properties;
    private final Clock clock;

    public Maintenance(FollowEdgeRepository edges, AuthorRepository authors, PostMediaService media, AccountPurger purger,
                       PostProperties properties, Clock clock) {
        this.edges = edges;
        this.authors = authors;
        this.media = media;
        this.purger = purger;
        this.properties = properties.maintenance();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${post.maintenance.interval:PT1H}", initialDelayString = "PT10M")
    public void runScheduled() {
        if (!properties.enabled()) {
            return;
        }
        try (CorrelationId.Scope ignored = CorrelationId.open(null)) {   // cada rodada ganha o próprio id nos logs
            run("poda de túmulos", this::pruneTombstones);
            run("varredura de mídia órfã", media::sweepOrphans);
            run("limpeza de contas excluídas", this::purgeDeletedAccounts);
        }
    }

    public int pruneTombstones() {
        OffsetDateTime limit = OffsetDateTime.ofInstant(clock.instant().minus(properties.tombstoneRetention()), ZoneOffset.UTC);
        return edges.pruneRemovedBefore(limit);
    }

    public int purgeDeletedAccounts() {
        int removed = 0;
        for (UUID userId : authors.deletedWithLeftovers(ACCOUNTS_PER_ROUND)) {
            removed += purger.purgeAccount(userId);
        }
        return removed;
    }

    private static void run(String name, java.util.function.IntSupplier task) {
        try {
            int removed = task.getAsInt();
            if (removed > 0) {
                log.info("Rotina '{}' removeu {} registro(s)", name, removed);
            }
        } catch (RuntimeException e) {
            log.warn("Rotina de manutenção '{}' falhou (tentará de novo na próxima rodada): {}", name, e.toString());
        }
    }
}
