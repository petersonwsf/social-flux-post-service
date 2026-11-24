package com.socialflux.post.moderation;

import com.socialflux.post.config.PostProperties;
import com.socialflux.post.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tradução do resultado da moderação em erros de negócio e política de falha do provedor. */
class ContentGuardTest {

    private static PostProperties.Moderation properties(boolean failClosedText) {
        return new PostProperties.Moderation(true, "chave", "http://localhost", "modelo", Duration.ofSeconds(1),
                failClosedText, new PostProperties.Thresholds(0.8, 0.8, 0.5, 0.7));
    }

    private static ContentGuard guard(boolean failClosedText, ContentModerator moderator) {
        return new ContentGuard(moderator, properties(failClosedText));
    }

    @Test
    void allowedTextPasses() {
        assertThatCode(() -> guard(false, text -> Verdict.ALLOWED).requireAllowedText("oi")).doesNotThrowAnyException();
    }

    @Test
    void nullTextIsNotAnalyzed() {
        AtomicInteger calls = new AtomicInteger();
        ContentGuard guard = guard(true, text -> {
            calls.incrementAndGet();
            return Verdict.ALLOWED;
        });
        guard.requireAllowedText(null);
        assertThat(calls).hasValue(0);
    }

    @Test
    void forbiddenTextIs422WithGenericMessage() {
        ContentGuard guard = guard(false, text -> new Verdict(Verdict.Kind.FORBIDDEN, "hate"));
        assertThatThrownBy(() -> guard.requireAllowedText("x"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.code()).isEqualTo("conteudo_proibido");
                    assertThat(e.getMessage()).doesNotContain("hate");
                });
    }

    @Test
    void selfHarmHasItsOwnCodeAndSupportMessage() {
        ContentGuard guard = guard(false, text -> new Verdict(Verdict.Kind.SELF_HARM, "self-harm"));
        assertThatThrownBy(() -> guard.requireAllowedText("x"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.code()).isEqualTo("conteudo_automutilacao");
                    assertThat(e.getMessage()).contains("188");
                });
    }

    @Test
    void providerFailureLetsTextPassWhenFailOpen() {
        ContentGuard guard = guard(false, text -> {
            throw new ModerationUnavailableException("fora do ar", null);
        });
        assertThatCode(() -> guard.requireAllowedText("oi")).doesNotThrowAnyException();
    }

    @Test
    void providerFailureIs503WhenFailClosed() {
        ContentGuard guard = guard(true, text -> {
            throw new ModerationUnavailableException("fora do ar", null);
        });
        assertThatThrownBy(() -> guard.requireAllowedText("oi"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("moderacao_indisponivel");
                });
    }
}
