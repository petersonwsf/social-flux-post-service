package com.socialflux.post.service;

import com.socialflux.post.config.PostProperties;
import com.socialflux.post.domain.PostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rede de segurança dos contadores ({@code likes_count}, {@code replies_count}). Eles são mantidos na mesma transação de cada
 * curtida/resposta/exclusão, então normalmente nunca divergem das linhas; esta rotina cobre o que os testes não pegaram (um
 * bug, um conserto manual no banco, uma operação em lote fora do serviço). A verdade são as linhas de {@code post_like} e as
 * respostas em {@code post}: o contador é só uma cópia para leitura rápida.
 * <ol>
 *   <li>uma consulta de LEITURA acha as postagens cujos contadores não batem (no máximo {@code batch-size} por rodada);</li>
 *   <li>para cada uma, uma transação CURTA trava a postagem, recalcula por {@code count(*)} e corrige — as curtidas e respostas
 *   travam a mesma linha, então a correção nunca disputa com uma escrita em andamento;</li>
 *   <li>registra quantas foram corrigidas: divergência frequente é sinal de bug a investigar, não algo para mascarar.</li>
 * </ol>
 */
@Component
public class CounterReconciler {

    private static final Logger log = LoggerFactory.getLogger(CounterReconciler.class);
    private static final int LOGGED_IDS = 10;

    /** Resultado de uma rodada: quantas postagens divergiam e quais foram corrigidas. */
    public record Result(int checked, List<PostRepository.CountCheck> fixed, boolean moreToDo) {

        public int fixedCount() {
            return fixed.size();
        }
    }

    private final PostRepository posts;
    private final PostProperties.Reconciler properties;
    private final TransactionTemplate tx;

    public CounterReconciler(PostRepository posts, PostProperties properties, PlatformTransactionManager txManager) {
        this.posts = posts;
        this.properties = properties.reconciler();
        this.tx = new TransactionTemplate(txManager);
    }

    /** Execução agendada (a cada {@code post.reconciler.interval}; a primeira, 5 minutos depois de subir). */
    @Scheduled(fixedDelayString = "${post.reconciler.interval:PT1H}", initialDelayString = "PT5M")
    public void runScheduled() {
        if (!properties.enabled()) {
            return;
        }
        try {
            reconcile();
        } catch (RuntimeException e) {
            log.warn("Reconciliador de contadores falhou (tentará de novo na próxima rodada): {}", e.toString());
        }
    }

    /** Uma rodada. Pode ser chamada direto (testes, manutenção). */
    public Result reconcile() {
        List<UUID> divergent = posts.findCountDivergences(properties.batchSize());
        List<PostRepository.CountCheck> fixed = new ArrayList<>();
        for (UUID postId : divergent) {
            Optional<PostRepository.CountCheck> check = tx.execute(status -> posts.reconcileCounts(postId))
                    .filter(PostRepository.CountCheck::diverges);
            check.ifPresent(fixed::add);
        }
        boolean moreToDo = divergent.size() >= properties.batchSize();
        if (!fixed.isEmpty()) {
            log.warn("Reconciliador: {} postagem(ns) com contadores divergentes corrigida(s) (primeiras: {}){}",
                    fixed.size(), fixed.stream().limit(LOGGED_IDS).map(PostRepository.CountCheck::postId).toList(),
                    moreToDo ? "; há mais divergências para a próxima rodada" : "");
        }
        return new Result(divergent.size(), List.copyOf(fixed), moreToDo);
    }
}
