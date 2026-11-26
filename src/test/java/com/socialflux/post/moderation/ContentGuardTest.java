package com.socialflux.post.moderation;

import com.socialflux.audit.AuditEventBuilder;
import com.socialflux.audit.InMemoryAuditRecorder;
import com.socialflux.audit.SocialFluxAuditProperties;
import com.socialflux.media.ProcessedMedia;
import com.socialflux.post.config.PostProperties;
import com.socialflux.post.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tradução do resultado da moderação em erros de negócio e política de falha do provedor. */
class ContentGuardTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final Verdict HATE = new Verdict(Verdict.Kind.FORBIDDEN, "hate");
    private static final Verdict SEXUAL = new Verdict(Verdict.Kind.FORBIDDEN, "sexual");
    private static final Verdict SELF_HARM = new Verdict(Verdict.Kind.SELF_HARM, "self-harm");

    private static ContentGuard guard(boolean failClosedText, boolean failClosedImages, ContentModerator moderator) {
        PostProperties.Moderation properties = new PostProperties.Moderation(true, "chave", "http://localhost", "modelo",
                Duration.ofSeconds(1), failClosedText, failClosedImages,
                new PostProperties.Thresholds(0.8, 0.8, 0.65, 0.7, 0.5, 0.25, 0.7));
        return new ContentGuard(moderator, properties, new InMemoryAuditRecorder(new AuditEventBuilder(
                new SocialFluxAuditProperties(false, "", "", "post", Duration.ofSeconds(2), 100, 50, Duration.ofSeconds(2),
                        Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofDays(7)),
                Clock.systemUTC(), JsonMapper.builder().build())));
    }

    private static ModerationUnavailableException down() {
        return new ModerationUnavailableException("fora do ar", null);
    }

    private static ProcessedMedia image(int width) {
        return new ProcessedMedia(new byte[]{(byte) width}, ProcessedMedia.JPEG, "jpg", width, width, ProcessedMedia.Kind.IMAGE);
    }

    private static ApiException refusal(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("Esperava uma recusa (ApiException)");
    }

    // ------------------------------------------------------------------ texto

    @Test
    void allowedTextPasses() {
        ContentGuard guard = guard(false, true, StubModerator.forText(text -> Verdict.ALLOWED));
        assertThatCode(() -> guard.requireAllowedText(ACTOR, "oi")).doesNotThrowAnyException();
    }

    @Test
    void nullTextIsNotAnalyzed() {
        AtomicInteger calls = new AtomicInteger();
        ContentGuard guard = guard(true, true, StubModerator.forText(text -> {
            calls.incrementAndGet();
            return Verdict.ALLOWED;
        }));
        guard.requireAllowedText(ACTOR, null);
        assertThat(calls).hasValue(0);
    }

    @Test
    void forbiddenTextIs422WithGenericMessage() {
        ContentGuard guard = guard(false, true, StubModerator.forText(text -> HATE));
        ApiException e = refusal(() -> guard.requireAllowedText(ACTOR, "x"));
        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(e.code()).isEqualTo("conteudo_proibido");
        assertThat(e.getMessage()).doesNotContain("hate");
    }

    @Test
    void selfHarmTextHasItsOwnCodeAndSupportMessage() {
        ContentGuard guard = guard(false, true, StubModerator.forText(text -> SELF_HARM));
        ApiException e = refusal(() -> guard.requireAllowedText(ACTOR, "x"));
        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(e.code()).isEqualTo("conteudo_automutilacao");
        assertThat(e.getMessage()).contains("188");
    }

    @Test
    void providerFailureLetsTextPassWhenFailOpen() {
        ContentGuard guard = guard(false, true, StubModerator.forText(text -> {
            throw down();
        }));
        assertThatCode(() -> guard.requireAllowedText(ACTOR, "oi")).doesNotThrowAnyException();
    }

    @Test
    void providerFailureIs503ForTextWhenFailClosed() {
        ContentGuard guard = guard(true, false, StubModerator.forText(text -> {
            throw down();
        }));
        ApiException e = refusal(() -> guard.requireAllowedText(ACTOR, "oi"));
        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.code()).isEqualTo("moderacao_indisponivel");
    }

    // ------------------------------------------------------------------ imagens

    @Test
    void noImagesAreNotAnalyzed() {
        AtomicInteger calls = new AtomicInteger();
        ContentGuard guard = guard(true, true, StubModerator.forImages(i -> {
            calls.incrementAndGet();
            return Verdict.ALLOWED;
        }));
        guard.requireAllowedImages(ACTOR, List.of());
        assertThat(calls).hasValue(0);
    }

    @Test
    void allowedImagesPassAndEachOneIsAnalyzed() {
        AtomicInteger calls = new AtomicInteger();
        ContentGuard guard = guard(false, true, StubModerator.forImages(i -> {
            calls.incrementAndGet();
            return Verdict.ALLOWED;
        }));
        guard.requireAllowedImages(ACTOR, List.of(image(1), image(2), image(3)));
        assertThat(calls).hasValue(3);
    }

    @Test
    void oneForbiddenImageRefusesThePost() {
        ContentGuard guard = guard(false, true, StubModerator.forImages(
                i -> i.bytes()[0] == 2 ? SEXUAL : Verdict.ALLOWED));
        ApiException e = refusal(() -> guard.requireAllowedImages(ACTOR, List.of(image(1), image(2), image(3))));
        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(e.code()).isEqualTo("conteudo_proibido");
    }

    @Test
    void selfHarmImageHasItsOwnCode() {
        ContentGuard guard = guard(false, true, StubModerator.forImages(i -> SELF_HARM));
        assertThat(refusal(() -> guard.requireAllowedImages(ACTOR, List.of(image(1)))).code()).isEqualTo("conteudo_automutilacao");
    }

    @Test
    void providerFailureIs503ForImagesWhenFailClosed() {
        ContentGuard guard = guard(false, true, StubModerator.forImages(i -> {
            throw down();
        }));
        ApiException e = refusal(() -> guard.requireAllowedImages(ACTOR, List.of(image(1))));
        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.code()).isEqualTo("moderacao_indisponivel");
    }

    @Test
    void providerFailureLetsImagesPassWhenFailOpen() {
        ContentGuard guard = guard(true, false, StubModerator.forImages(i -> {
            throw down();
        }));
        assertThatCode(() -> guard.requireAllowedImages(ACTOR, List.of(image(1)))).doesNotThrowAnyException();
    }

    @Test
    void forbiddenImageWinsOverProviderFailureOnAnother() {
        ContentGuard guard = guard(false, true, StubModerator.forImages(i -> {
            if (i.bytes()[0] == 1) {
                throw down();
            }
            return SEXUAL;
        }));
        assertThat(refusal(() -> guard.requireAllowedImages(ACTOR, List.of(image(1), image(2)))).code())
                .isEqualTo("conteudo_proibido");
    }

    @Test
    void unexpectedErrorOnAnImageFollowsTheFailurePolicy() {
        ContentGuard closed = guard(false, true, StubModerator.forImages(i -> {
            throw new IllegalStateException("bug");
        }));
        assertThat(refusal(() -> closed.requireAllowedImages(ACTOR, List.of(image(1)))).code()).isEqualTo("moderacao_indisponivel");
    }

    @Test
    void imagesAreAnalyzedInParallel() {
        // 3 imagens de ~300 ms cada: em paralelo levam bem menos que a soma (900 ms)
        ContentGuard guard = guard(false, true, StubModerator.forImages(i -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Verdict.ALLOWED;
        }));
        long start = System.nanoTime();
        guard.requireAllowedImages(ACTOR, List.of(image(1), image(2), image(3)));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(700));
    }
}
