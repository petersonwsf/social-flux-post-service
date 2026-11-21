package com.socialflux.post.service;

import com.socialflux.post.config.PostProperties;
import com.socialflux.post.domain.FollowEdgeRepository;
import com.socialflux.post.domain.PostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Apaga o conteúdo de uma conta excluída (D12): postagens (com as respostas dependentes, as curtidas recebidas e a mídia),
 * as curtidas que ela deu (ajustando os contadores alheios) e as arestas. Cada postagem e cada curtida vai numa transação
 * CURTA — nunca uma transação gigante — e tudo é idempotente: se cair no meio, a próxima rodada (agendada) continua.
 * O conteúdo já está invisível desde o {@code profile.deleted} (a conta fica {@code DELETED}).
 */
@Component
public class AccountPurger {

    private static final Logger log = LoggerFactory.getLogger(AccountPurger.class);
    private static final int MAX_ROUNDS = 1_000;

    private final PostRepository posts;
    private final FollowEdgeRepository edges;
    private final PostWriter writer;
    private final TransactionTemplate tx;
    private final int batchSize;

    public AccountPurger(PostRepository posts, FollowEdgeRepository edges, PostWriter writer, PostProperties properties,
                         PlatformTransactionManager txManager) {
        this.posts = posts;
        this.edges = edges;
        this.writer = writer;
        // REQUIRES_NEW: a limpeza também roda no afterCommit da transação do evento, onde uma transação "REQUIRED" entraria
        // na transação já confirmada (e nada seria gravado nem as imagens apagadas).
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.batchSize = properties.maintenance().purgeBatchSize();
    }

    /** Devolve quantas postagens e curtidas foram removidas. */
    public int purgeAccount(UUID userId) {
        int removed = purgePosts(userId) + purgeLikes(userId);
        edges.deleteAllOf(userId);
        if (removed > 0) {
            log.info("Conta excluída {}: {} postagem(ns)/curtida(s) removida(s)", userId, removed);
        }
        return removed;
    }

    private int purgePosts(UUID userId) {
        int removed = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<UUID> batch = posts.idsByAuthor(userId, batchSize);
            if (batch.isEmpty()) {
                return removed;
            }
            int before = removed;
            for (UUID id : batch) {
                Integer count = tx.execute(status -> {
                    posts.parentOf(id).ifPresent(parent -> posts.lockForUpdate(userId, parent));
                    return posts.lockForUpdate(userId, id)
                            .map(post -> writer.deleteTree(post.id(), post.parentId()))
                            .orElse(0);   // já saiu (era resposta de outra postagem da conta, apagada em cascata)
                });
                removed += count == null ? 0 : count;
            }
            if (removed == before) {
                return removed;   // nada mudou: evita laço infinito
            }
        }
        return removed;
    }

    private int purgeLikes(UUID userId) {
        int removed = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<UUID> batch = posts.likedBy(userId, batchSize);
            if (batch.isEmpty()) {
                return removed;
            }
            int before = removed;
            for (UUID postId : batch) {
                Boolean changed = tx.execute(status -> posts.lockForUpdate(userId, postId)
                        .map(post -> writer.unlike(post.id(), post.authorId(), userId))
                        .orElse(false));
                removed += Boolean.TRUE.equals(changed) ? 1 : 0;
            }
            if (removed == before) {
                return removed;
            }
        }
        return removed;
    }
}
