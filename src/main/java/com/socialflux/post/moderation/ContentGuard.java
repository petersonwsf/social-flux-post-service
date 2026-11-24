package com.socialflux.post.moderation;

import com.socialflux.media.ProcessedMedia;
import com.socialflux.post.config.PostProperties;
import com.socialflux.post.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
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

    static final String MSG_FORBIDDEN = "Sua postagem viola as regras de conteúdo da plataforma.";
    static final String MSG_SELF_HARM = "Sua postagem não foi publicada. Se você está passando por um momento difícil, "
            + "procure apoio: ligue 188 (CVV, 24 horas, gratuito) ou acesse cvv.org.br.";
    static final String MSG_UNAVAILABLE = "Não foi possível verificar o conteúdo agora. Tente novamente em instantes.";

    private final ContentModerator moderator;
    private final boolean failClosedText;
    private final boolean failClosedImages;
    /** As imagens de uma postagem (até 5) são consultadas em paralelo: a espera é a de uma chamada, não a soma. */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public ContentGuard(ContentModerator moderator, PostProperties properties) {
        this(moderator, properties.moderation());
    }

    ContentGuard(ContentModerator moderator, PostProperties.Moderation properties) {
        this.moderator = moderator;
        this.failClosedText = properties.failClosedText();
        this.failClosedImages = properties.failClosedImages();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Recusa o texto que viola as regras. Texto nulo (postagem só com imagem) não é analisado. */
    public void requireAllowedText(String text) {
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
        refuseIfBlocked(verdict);
    }

    /**
     * Recusa a postagem se alguma imagem viola as regras. GIF é analisado pelo primeiro quadro. Se uma imagem for recusada
     * a postagem é recusada, mesmo que o provedor tenha falhado em outra.
     */
    public void requireAllowedImages(List<ProcessedMedia> medias) {
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
            refuseIfBlocked(blocked);
        }
        if (failure != null) {
            if (failClosedImages) {
                log.warn("Moderação indisponível; imagens recusadas: {}", failure);
                throw ApiException.unavailable("moderacao_indisponivel", MSG_UNAVAILABLE);
            }
            log.warn("Moderação indisponível; imagens liberadas: {}", failure);
        }
    }

    private static void refuseIfBlocked(Verdict verdict) {
        switch (verdict.kind()) {
            case ALLOWED -> {
            }
            case SELF_HARM -> {
                log.info("Postagem recusada na moderação: categoria {}", verdict.category());
                throw ApiException.unprocessable("conteudo_automutilacao", MSG_SELF_HARM);
            }
            case FORBIDDEN -> {
                log.info("Postagem recusada na moderação: categoria {}", verdict.category());
                throw ApiException.unprocessable("conteudo_proibido", MSG_FORBIDDEN);
            }
        }
    }
}
