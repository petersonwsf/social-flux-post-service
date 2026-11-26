package com.socialflux.post.moderation;

import com.socialflux.audit.AuditEntry;
import com.socialflux.audit.AuditRecorder;
import com.socialflux.audit.Outcome;
import com.socialflux.media.ProcessedMedia;
import com.socialflux.post.config.PostProperties;
import com.socialflux.post.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Porta de entrada da moderação para as regras de postagem: consulta o {@link ContentModerator} e traduz o resultado em
 * erro de negócio (422) ou, se o provedor falhar, deixa passar ou recusa com 503 conforme {@code fail-closed-text} e
 * {@code fail-closed-images}. O conteúdo analisado nunca vai para o log.
 */
@Service
public class ContentGuard {

    private static final Logger log = LoggerFactory.getLogger(ContentGuard.class);

    private static final String CONTENT_TEXT = "TEXT";
    private static final String CONTENT_IMAGE = "IMAGE";

    static final String MSG_FORBIDDEN = "Sua postagem viola as regras de conteúdo da plataforma.";
    static final String MSG_SELF_HARM = "Sua postagem não foi publicada. Se você está passando por um momento difícil, "
            + "procure apoio: ligue 188 (CVV, 24 horas, gratuito) ou acesse cvv.org.br.";
    static final String MSG_UNAVAILABLE = "Não foi possível verificar o conteúdo agora. Tente novamente em instantes.";

    private final ContentModerator moderator;
    private final boolean failClosedText;
    private final boolean failClosedImages;
    private final AuditRecorder audit;
    /** As imagens de uma postagem (até 5) são consultadas em paralelo: a espera é a de uma chamada, não a soma. */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public ContentGuard(ContentModerator moderator, PostProperties properties, AuditRecorder audit) {
        this(moderator, properties.moderation(), audit);
    }

    ContentGuard(ContentModerator moderator, PostProperties.Moderation properties, AuditRecorder audit) {
        this.moderator = moderator;
        this.audit = audit;
        this.failClosedText = properties.failClosedText();
        this.failClosedImages = properties.failClosedImages();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Recusa o texto que viola as regras. Texto nulo (postagem só com imagem) não é analisado. A recusa vai para a
     * auditoria (sem o texto) em nome de {@code actorId}.
     */
    public void requireAllowedText(UUID actorId, String text) {
        if (text == null) {
            return;
        }
        Verdict verdict;
        try {
            verdict = moderator.checkText(text);
        } catch (ModerationUnavailableException e) {
            if (failClosedText) {
                log.warn("Moderação indisponível; texto recusado: {}", e.getMessage());
                throw ApiException.unavailable("moderacao_indisponivel", MSG_UNAVAILABLE);
            }
            log.warn("Moderação indisponível; texto liberado: {}", e.getMessage());
            return;
        }
        refuseIfBlocked(actorId, CONTENT_TEXT, verdict);
    }

    /**
     * Recusa a postagem se alguma imagem viola as regras. GIF é analisado pelo primeiro quadro. Se uma imagem for recusada
     * a postagem é recusada, mesmo que o provedor tenha falhado em outra.
     */
    public void requireAllowedImages(UUID actorId, List<ProcessedMedia> medias) {
        if (medias.isEmpty()) {
            return;
        }
        List<Future<Verdict>> futures = medias.stream()
                .map(media -> executor.submit(() -> moderator.checkImage(ModerationImages.prepare(media))))
                .toList();
        Verdict blocked = null;
        String failure = null;
        for (Future<Verdict> future : futures) {
            try {
                Verdict verdict = future.get();
                if (!verdict.allowed() && blocked == null) {
                    blocked = verdict;
                }
            } catch (ExecutionException e) {
                failure = e.getCause() instanceof ModerationUnavailableException
                        ? e.getCause().getMessage() : "erro inesperado: " + e.getCause().getClass().getSimpleName();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = "consulta interrompida";
            }
        }
        if (blocked != null) {
            refuseIfBlocked(actorId, CONTENT_IMAGE, blocked);
        }
        if (failure != null) {
            if (failClosedImages) {
                log.warn("Moderação indisponível; imagens recusadas: {}", failure);
                throw ApiException.unavailable("moderacao_indisponivel", MSG_UNAVAILABLE);
            }
            log.warn("Moderação indisponível; imagens liberadas: {}", failure);
        }
    }

    private void refuseIfBlocked(UUID actorId, String contentType, Verdict verdict) {
        switch (verdict.kind()) {
            case ALLOWED -> {
            }
            case SELF_HARM -> {
                log.info("Postagem recusada na moderação: categoria {}", verdict.category());
                auditRefusal(actorId, contentType, verdict);
                throw ApiException.unprocessable("conteudo_automutilacao", MSG_SELF_HARM);
            }
            case FORBIDDEN -> {
                log.info("Postagem recusada na moderação: categoria {}", verdict.category());
                auditRefusal(actorId, contentType, verdict);
                throw ApiException.unprocessable("conteudo_proibido", MSG_FORBIDDEN);
            }
        }
    }

    /**
     * Auditoria da recusa em transação própria (sobrevive ao rollback do pedido): tipo do conteúdo, veredito, categoria e
     * score. NUNCA o texto nem a imagem.
     */
    private void auditRefusal(UUID actorId, String contentType, Verdict verdict) {
        AuditEntry entry = AuditEntry.of("CONTENT_REFUSED", Outcome.DENIED).actor(actorId)
                .detail("contentType", contentType)
                .detail("verdict", verdict.kind().name())
                .detail("category", verdict.category());
        if (verdict.score() != null) {
            entry = entry.detail("score", Math.round(verdict.score() * 1000.0) / 1000.0);
        }
        audit.independent(entry);
    }
}
