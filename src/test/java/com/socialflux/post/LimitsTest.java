package com.socialflux.post;

import com.socialflux.post.support.AbstractPostTest;
import com.socialflux.post.support.Http;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Limites por usuário (D10), com valores baixos só neste teste. */
@TestPropertySource(properties = {"post.limits.posts-per-hour=3", "post.limits.likes-per-hour=2", "post.limits.max-body=10"})
class LimitsTest extends AbstractPostTest {

    @Test
    void postsPerHourAreLimitedPerUser() {
        TestUser ana = newUser();
        for (int i = 0; i < 3; i++) {
            assertThat(createPost(ana, "p" + i).status()).isEqualTo(201);
        }
        Http.Response limited = createPost(ana, "demais");
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.json()).containsEntry("codigo", "muitas_requisicoes");
        assertThat(Long.parseLong(limited.header("Retry-After"))).isPositive();
        assertThat(createPost(newUser(), "outra").status()).isEqualTo(201);
    }

    @Test
    void likesPerHourAreLimitedPerUser() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        String id = (String) createPost(ana, "curta").json().get("id");
        assertThat(like(bia, id).status()).isEqualTo(200);
        assertThat(unlike(bia, id).status()).isEqualTo(200);
        assertThat(like(bia, id).status()).isEqualTo(429);
    }

    @Test
    void maxBodyIsConfigurable() {
        TestUser ana = newUser();
        assertThat(createPost(ana, "0123456789").status()).isEqualTo(201);
        assertThat(createPost(ana, "0123456789X").json()).containsEntry("codigo", "texto_muito_longo");
    }
}
