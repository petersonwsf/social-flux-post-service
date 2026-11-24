package com.socialflux.post.moderation;

import com.socialflux.post.config.PostProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Regras puras de decisão (sem rede e sem Spring). */
class ModerationPolicyTest {

    private final ModerationPolicy policy =
            new ModerationPolicy(new PostProperties.Thresholds(0.80, 0.80, 0.65, 0.70, 0.50, 0.25, 0.70));

    @Test
    void cleanTextIsAllowed() {
        assertThat(policy.evaluateText(Map.of("hate", 0.01, "self-harm", 0.0)).allowed()).isTrue();
    }

    @Test
    void ordinaryOffenseIsAllowed() {
        Verdict verdict = policy.evaluateText(Map.of("harassment", 0.99, "violence", 0.95, "illicit", 0.9, "hate", 0.30));
        assertThat(verdict.allowed()).isTrue();
    }

    @Test
    void heavyHateIsForbidden() {
        Verdict verdict = policy.evaluateText(Map.of("hate", 0.80));
        assertThat(verdict.kind()).isEqualTo(Verdict.Kind.FORBIDDEN);
        assertThat(verdict.category()).isEqualTo("hate");
    }

    @Test
    void hateJustBelowTheLimitPasses() {
        assertThat(policy.evaluateText(Map.of("hate", 0.79)).allowed()).isTrue();
    }

    @Test
    void deathWishAtSomeoneIsForbidden() {
        // "fulano tem que morrer": medido na API com harassment/threatening 0,74 e violence 0,77
        Verdict verdict = policy.evaluateText(Map.of("harassment/threatening", 0.74, "violence", 0.77, "harassment", 0.65));
        assertThat(verdict.kind()).isEqualTo(Verdict.Kind.FORBIDDEN);
        assertThat(verdict.category()).isEqualTo("harassment/threatening");
    }

    @Test
    void jokingViolenceIsAllowed() {
        // "eu mato quem mexer no meu lanche kkk": violence 0,79 mas harassment/threatening só 0,29
        Verdict verdict = policy.evaluateText(Map.of("violence", 0.79, "harassment/threatening", 0.29, "harassment", 0.20));
        assertThat(verdict.allowed()).isTrue();
    }

    @Test
    void threateningHateIsForbidden() {
        assertThat(policy.evaluateText(Map.of("hate/threatening", 0.9)).kind()).isEqualTo(Verdict.Kind.FORBIDDEN);
    }

    @Test
    void graphicViolenceIsForbidden() {
        assertThat(policy.evaluateText(Map.of("violence/graphic", 0.75)).kind()).isEqualTo(Verdict.Kind.FORBIDDEN);
    }

    @Test
    void anySelfHarmCategoryIsSelfHarm() {
        for (String category : new String[]{"self-harm", "self-harm/intent", "self-harm/instructions"}) {
            Verdict verdict = policy.evaluateText(Map.of(category, 0.5));
            assertThat(verdict.kind()).as(category).isEqualTo(Verdict.Kind.SELF_HARM);
            assertThat(verdict.category()).isEqualTo(category);
        }
    }

    @Test
    void selfHarmWinsOverOtherCategories() {
        Verdict verdict = policy.evaluateText(Map.of("self-harm/intent", 0.9, "hate", 0.95, "violence/graphic", 0.9));
        assertThat(verdict.kind()).isEqualTo(Verdict.Kind.SELF_HARM);
    }

    @Test
    void missingScoresAreIgnored() {
        assertThat(policy.evaluateText(Map.of()).allowed()).isTrue();
    }

    @Test
    void sexualImageIsForbidden() {
        Verdict verdict = policy.evaluateImage(Map.of("sexual", 0.70));
        assertThat(verdict.kind()).isEqualTo(Verdict.Kind.FORBIDDEN);
        assertThat(verdict.category()).isEqualTo("sexual");
    }

    @Test
    void sexualImageJustBelowTheLimitPasses() {
        assertThat(policy.evaluateImage(Map.of("sexual", 0.69)).allowed()).isTrue();
    }

    @Test
    void graphicViolenceImageIsForbidden() {
        // foto de sangue e cadáver, medida na API: violence/graphic 0,85 e violence 0,94
        Verdict verdict = policy.evaluateImage(Map.of("violence/graphic", 0.85, "violence", 0.94));
        assertThat(verdict.kind()).isEqualTo(Verdict.Kind.FORBIDDEN);
        assertThat(verdict.category()).isEqualTo("violence/graphic");
    }

    @Test
    void plainViolenceImageIsAllowed() {
        assertThat(policy.evaluateImage(Map.of("violence", 0.95)).allowed()).isTrue();
    }

    @Test
    void selfHarmImageIsSelfHarmAndWinsOverSexual() {
        Verdict verdict = policy.evaluateImage(Map.of("self-harm", 0.6, "sexual", 0.99));
        assertThat(verdict.kind()).isEqualTo(Verdict.Kind.SELF_HARM);
    }

    @Test
    void hateScoreDoesNotBlockAnImage() {
        // o provedor não avalia ódio em imagem; um score de texto que apareça por engano não deve bloquear
        assertThat(policy.evaluateImage(Map.of("hate", 0.99, "harassment/threatening", 0.99)).allowed()).isTrue();
    }

    @Test
    void selfHarmImageUsesTheLowerImageThreshold() {
        // foto de braço com marcas, medida na API: self-harm 0,288 (a API já a marca)
        Verdict verdict = policy.evaluateImage(Map.of("self-harm", 0.288, "violence", 0.186));
        assertThat(verdict.kind()).isEqualTo(Verdict.Kind.SELF_HARM);
        assertThat(verdict.category()).isEqualTo("self-harm");
    }

    @Test
    void selfHarmImageJustBelowTheImageLimitPasses() {
        assertThat(policy.evaluateImage(Map.of("self-harm", 0.24)).allowed()).isTrue();
    }

    @Test
    void sameSelfHarmScoreInTextStillPasses() {
        // o limiar mais baixo vale só para imagem: no texto (0,50) a mesma pontuação não bloqueia
        assertThat(policy.evaluateText(Map.of("self-harm", 0.288)).allowed()).isTrue();
    }
}
