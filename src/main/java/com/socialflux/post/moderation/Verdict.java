package com.socialflux.post.moderation;

/**
 * Resultado da moderação de um conteúdo. {@code category} e {@code score} só servem para log e auditoria (nunca vão ao
 * cliente, para não ensinar a contornar o filtro).
 */
public record Verdict(Kind kind, String category, Double score) {

    public enum Kind {
        /** Pode publicar. */
        ALLOWED,
        /** Viola as regras (ódio pesado, violência gráfica). */
        FORBIDDEN,
        /** Automutilação: recusa com mensagem de apoio. */
        SELF_HARM
    }

    public static final Verdict ALLOWED = new Verdict(Kind.ALLOWED, null, null);

    /** Sem score (testes e moderadores que não o informam). */
    public Verdict(Kind kind, String category) {
        this(kind, category, null);
    }

    public boolean allowed() {
        return kind == Kind.ALLOWED;
    }
}
