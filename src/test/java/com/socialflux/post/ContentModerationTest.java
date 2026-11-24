package com.socialflux.post;

import com.socialflux.post.moderation.ContentModerator;
import com.socialflux.post.moderation.ModerationImage;
import com.socialflux.post.moderation.ModerationUnavailableException;
import com.socialflux.post.moderation.Verdict;
import com.socialflux.post.support.AbstractPostTest;
import com.socialflux.post.support.Http;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Moderação de texto e imagens na criação de postagens e respostas. O moderador falso decide pelo próprio conteúdo: no
 * texto, por marcadores; na imagem, pela largura (33 = proibida, 44 = automutilação, 55 = provedor fora do ar).
 */
@Import(ContentModerationTest.FakeModeratorConfig.class)
class ContentModerationTest extends AbstractPostTest {

    private static final int FORBIDDEN_WIDTH = 33;
    private static final int SELF_HARM_WIDTH = 44;
    private static final int DOWN_WIDTH = 55;

    @TestConfiguration
    static class FakeModeratorConfig {

        @Bean
        @Primary
        ContentModerator fakeModerator() {
            return new ContentModerator() {
                @Override
                public Verdict checkText(String text) {
                    if (text.contains("[odio]")) {
                        return new Verdict(Verdict.Kind.FORBIDDEN, "hate");
                    }
                    if (text.contains("[automutilacao]")) {
                        return new Verdict(Verdict.Kind.SELF_HARM, "self-harm");
                    }
                    return Verdict.ALLOWED;
                }

                @Override
                public Verdict checkImage(ModerationImage image) {
                    return switch (widthOf(image)) {
                        case FORBIDDEN_WIDTH -> new Verdict(Verdict.Kind.FORBIDDEN, "sexual");
                        case SELF_HARM_WIDTH -> new Verdict(Verdict.Kind.SELF_HARM, "self-harm");
                        case DOWN_WIDTH -> throw new ModerationUnavailableException("fora do ar (simulado)", null);
                        default -> Verdict.ALLOWED;
                    };
                }

                private int widthOf(ModerationImage image) {
                    try {
                        return ImageIO.read(new ByteArrayInputStream(image.bytes())).getWidth();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            };
        }
    }

    private long postCount() {
        return jdbc.sql("select count(*) from post").query(Long.class).single();
    }

    /** GIF de um quadro que cobre a tela inteira (o helper da base usa quadros 1×1, que o decodificador devolve em 1×1). */
    private static byte[] fullFrameGif(int width, int height) {
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            ImageIO.write(new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB), "gif", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Http.Response createWithImages(TestUser author, List<byte[]> images) {
        return http().multipartFiles("POST", "/api/postagens", author.token, "imagens", images, Map.of());
    }

    // ------------------------------------------------------------------ texto

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

    // ------------------------------------------------------------------ imagens

    @Test
    void cleanImagesArePublished() {
        TestUser ana = newUser();
        assertThat(createWithImages(ana, List.of(png(20, 20), png(30, 30))).status()).isEqualTo(201);
    }

    @Test
    void forbiddenImageIsRefusedAndNothingIsStored() {
        TestUser ana = newUser();
        long before = postCount();
        int objectsBefore = storage().count(BUCKET);
        Http.Response response = createPost(ana, "legenda inocente", null, png(FORBIDDEN_WIDTH, 40));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json()).containsEntry("codigo", "conteudo_proibido").containsEntry("sucesso", false);
        assertThat(response.body()).doesNotContain("sexual");
        assertThat(postCount()).isEqualTo(before);
        assertThat(storage().count(BUCKET)).isEqualTo(objectsBefore);
    }

    @Test
    void selfHarmImageIsRefusedWithSupportMessage() {
        TestUser ana = newUser();
        Http.Response response = createPost(ana, null, null, png(SELF_HARM_WIDTH, 40));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json()).containsEntry("codigo", "conteudo_automutilacao");
        assertThat((String) response.json().get("mensagem")).contains("188");
    }

    @Test
    void oneForbiddenImageAmongFiveRefusesTheWholePost() {
        TestUser ana = newUser();
        long before = postCount();
        int objectsBefore = storage().count(BUCKET);
        Http.Response response = createWithImages(ana,
                List.of(png(20, 20), png(21, 21), png(FORBIDDEN_WIDTH, 40), png(22, 22), png(23, 23)));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json()).containsEntry("codigo", "conteudo_proibido");
        assertThat(postCount()).isEqualTo(before);
        assertThat(storage().count(BUCKET)).isEqualTo(objectsBefore);
    }

    @Test
    void gifIsModeratedByItsFirstFrame() {
        TestUser ana = newUser();
        long before = postCount();
        Http.Response response = createWithImages(ana, List.of(fullFrameGif(FORBIDDEN_WIDTH, 40)));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json()).containsEntry("codigo", "conteudo_proibido");
        assertThat(postCount()).isEqualTo(before);
        assertThat(createWithImages(ana, List.of(gif(20, 20, 3))).status()).isEqualTo(201);
    }

    @Test
    void providerFailureOnImagesIs503AndNothingIsStored() {
        TestUser ana = newUser();
        long before = postCount();
        int objectsBefore = storage().count(BUCKET);
        Http.Response response = createPost(ana, "oi", null, png(DOWN_WIDTH, 40));
        assertThat(response.status()).isEqualTo(503);
        assertThat(response.json()).containsEntry("codigo", "moderacao_indisponivel");
        assertThat(postCount()).isEqualTo(before);
        assertThat(storage().count(BUCKET)).isEqualTo(objectsBefore);
    }

    @Test
    void repliesWithForbiddenImagesAreRefused() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        String id = post(ana, "postagem normal");
        Http.Response response = createPost(bia, "resposta", id, png(FORBIDDEN_WIDTH, 40));
        assertThat(response.status()).isEqualTo(422);
        assertThat(count("select count(*) from post where parent_id = :id", UUID.fromString(id))).isZero();
    }
}
