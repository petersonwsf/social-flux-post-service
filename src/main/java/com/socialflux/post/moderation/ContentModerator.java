package com.socialflux.post.moderation;

/** Analisa o conteúdo de uma postagem. Quem decide o que fazer quando o provedor falha é o {@link ContentGuard}. */
public interface ContentModerator {

    /**
     * Analisa um texto.
     *
     * @throws ModerationUnavailableException se o provedor falhar (timeout, erro HTTP, resposta ilegível)
     */
    Verdict checkText(String text);
}
