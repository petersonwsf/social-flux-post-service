package com.socialflux.post.moderation;

/**
 * Resultado da moderação de um conteúdo. {@code category} só serve para log (nunca vai ao cliente, para não ensinar a
 * contornar o filtro).
 */
public record Verdict(Kind kind, String category) {

    public enum Kind {
        /** Pode publicar. */
        ALLOWED,
        /** Viola as regras (ódio pesado, violência gráfica). */
        FORBIDDEN,
        /** Automutilação: recusa com mensagem de apoio. */
        SELF_HARM
    }

    public static final Verdict ALLOWED = new Verdict(Kind.ALLOWED, null);

    public boolean allowed() {
        return kind == Kind.ALLOWED;
    }
}
