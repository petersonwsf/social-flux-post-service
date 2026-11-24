package com.socialflux.post;

import com.socialflux.post.moderation.ContentModerator;
import com.socialflux.post.moderation.Verdict;
import com.socialflux.post.support.AbstractPostTest;
import com.socialflux.post.support.Http;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Moderação de texto na criação de postagens e respostas (moderador falso, decidido pelo próprio texto). */
@Import(ContentModerationTest.FakeModeratorConfig.class)
class ContentModerationTest extends AbstractPostTest {

    @TestConfiguration
    static class FakeModeratorConfig {

        @Bean
        @Primary
        ContentModerator fakeModerator() {
            return text -> {
                if (text.contains("[odio]")) {
                    return new Verdict(Verdict.Kind.FORBIDDEN, "hate");
                }
                if (text.contains("[automutilacao]")) {
                    return new Verdict(Verdict.Kind.SELF_HARM, "self-harm");
                }
                return Verdict.ALLOWED;
            };
        }
    }

    private long postCount() {
        return jdbc.sql("select count(*) from post").query(Long.class).single();
    }

    @Test
    void cleanTextIsPublished() {
        TestUser ana = newUser();
        assertThat(createPost(ana, "um texto qualquer, até com uma ofensa comum").status()).isEqualTo(201);
    }

    @Test
    void forbiddenTextIsRefusedAndNothingIsStored() {
        TestUser ana = newUser();
        long before = postCount();
        int objectsBefore = storage().count(BUCKET);
        Http.Response response = createPost(ana, "texto [odio]", null, png(20, 20));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json()).containsEntry("codigo", "conteudo_proibido").containsEntry("sucesso", false);
        assertThat(response.body()).doesNotContain("odio");
        assertThat(postCount()).isEqualTo(before);
        assertThat(storage().count(BUCKET)).isEqualTo(objectsBefore);
    }

    @Test
    void selfHarmTextIsRefusedWithSupportMessage() {
        TestUser ana = newUser();
        Http.Response response = createPost(ana, "texto [automutilacao]");
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json()).containsEntry("codigo", "conteudo_automutilacao");
        assertThat((String) response.json().get("mensagem")).contains("188");
    }

    @Test
    void repliesAreModeratedToo() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        String id = post(ana, "postagem normal");
        Http.Response response = createPost(bia, "resposta [odio]", id, null);
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json()).containsEntry("codigo", "conteudo_proibido");
        assertThat(count("select count(*) from post where parent_id = :id", UUID.fromString(id))).isZero();
    }

    @Test
    void imageOnlyPostIsNotTextModerated() {
        TestUser ana = newUser();
        assertThat(createPost(ana, null, null, png(20, 20)).status()).isEqualTo(201);
    }
}
