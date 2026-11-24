package com.socialflux.post.moderation;

import com.socialflux.post.config.PostProperties;

import java.util.Map;

/**
 * Regras puras: recebe os scores por categoria e decide. Ofensa comum ({@code harassment}), {@code violence} (pega
 * brincadeira e notícia) e {@code illicit} não bloqueiam: só ódio, ameaça ou desejo de morte a alguém
 * ({@code harassment/threatening}), violência gráfica e automutilação.
 */
public class ModerationPolicy {

    public static final String HATE = "hate";
    public static final String HATE_THREATENING = "hate/threatening";
    public static final String HARASSMENT_THREATENING = "harassment/threatening";
    public static final String SELF_HARM = "self-harm";
    public static final String SELF_HARM_INTENT = "self-harm/intent";
    public static final String SELF_HARM_INSTRUCTIONS = "self-harm/instructions";
    public static final String VIOLENCE_GRAPHIC = "violence/graphic";

    private final PostProperties.Thresholds thresholds;

    public ModerationPolicy(PostProperties.Thresholds thresholds) {
        this.thresholds = thresholds;
    }

    /** Avalia os scores de um texto. Automutilação vem primeiro, para a mensagem de apoio prevalecer. */
    public Verdict evaluateText(Map<String, Double> scores) {
        for (String category : new String[]{SELF_HARM, SELF_HARM_INTENT, SELF_HARM_INSTRUCTIONS}) {
            if (reaches(scores, category, thresholds.selfHarm())) {
                return new Verdict(Verdict.Kind.SELF_HARM, category);
            }
        }
        if (reaches(scores, HATE_THREATENING, thresholds.hateThreatening())) {
            return new Verdict(Verdict.Kind.FORBIDDEN, HATE_THREATENING);
        }
        if (reaches(scores, HARASSMENT_THREATENING, thresholds.harassmentThreatening())) {
            return new Verdict(Verdict.Kind.FORBIDDEN, HARASSMENT_THREATENING);
        }
        if (reaches(scores, HATE, thresholds.hate())) {
            return new Verdict(Verdict.Kind.FORBIDDEN, HATE);
        }
        if (reaches(scores, VIOLENCE_GRAPHIC, thresholds.violenceGraphic())) {
            return new Verdict(Verdict.Kind.FORBIDDEN, VIOLENCE_GRAPHIC);
        }
        return Verdict.ALLOWED;
    }

    private static boolean reaches(Map<String, Double> scores, String category, double threshold) {
        Double score = scores.get(category);
        return score != null && score >= threshold;
    }
}
