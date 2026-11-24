package com.socialflux.post.moderation;

import com.socialflux.post.config.PostProperties;
import com.socialflux.post.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Porta de entrada da moderação para as regras de postagem: consulta o {@link ContentModerator} e traduz o resultado em
 * erro de negócio (422) ou, se o provedor falhar, deixa passar ou recusa com 503 conforme {@code fail-closed-text}.
 * O texto analisado nunca vai para o log.
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

    @Autowired
    public ContentGuard(ContentModerator moderator, PostProperties properties) {
        this(moderator, properties.moderation());
    }

    ContentGuard(ContentModerator moderator, PostProperties.Moderation properties) {
        this.moderator = moderator;
        this.failClosedText = properties.failClosedText();
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
