package com.socialflux.post;

import com.socialflux.post.support.AbstractPostTest;
import com.socialflux.post.support.Http;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Várias imagens por postagem/resposta: até 5, em ordem, GIF só sozinho, entrega por visibilidade e limpeza ao excluir. */
class MultiImageTest extends AbstractPostTest {

    private Http.Response createWithImages(TestUser author, String text, String parentId, List<byte[]> images) {
        Map<String, String> fields = new HashMap<>();
        if (text != null) {
            fields.put("legenda", text);
        }
        if (parentId != null) {
            fields.put("pai", parentId);
        }
        return http().multipartFiles("POST", "/api/postagens", author.token, "imagens", images, fields);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> images(Http.Response response) {
        return (List<Map<String, Object>>) response.json().get("imagens");
    }

    private static String keyOf(Object url) {
        String s = (String) url;
        return s.substring(s.lastIndexOf('/') + 1);
    }

    @Test
    void fiveImagesAreKeptInOrderAndServed() {
        TestUser ana = newUser();
        TestUser fan = newUser();
        List<byte[]> uploads = List.of(png(100, 50), png(110, 50), png(120, 50), png(130, 50), png(140, 50));
        Http.Response created = createWithImages(ana, "cinco fotos", null, uploads);
        assertThat(created.status()).as(created.body()).isEqualTo(201);

        List<Map<String, Object>> images = images(created);
        assertThat(images).extracting(i -> i.get("largura")).containsExactly(100, 110, 120, 130, 140);
        @SuppressWarnings("unchecked")
        Map<String, Object> cover = (Map<String, Object>) created.json().get("imagem");
        assertThat(cover.get("url")).isEqualTo(images.get(0).get("url"));
        for (Map<String, Object> image : images) {
            assertThat(get(fan, (String) image.get("url")).status()).isEqualTo(200);
        }

        String id = (String) created.json().get("id");
        assertThat(images(get(ana, "/api/postagens/" + id))).hasSize(5);
        assertThat(ids(get(ana, "/api/postagens/feed"))).contains(id);
    }

    @Test
    void moreThanFiveImagesAreRejectedWithoutStoringAnything() {
        TestUser ana = newUser();
        long before = storage().count(BUCKET);
        Http.Response response = createWithImages(ana, "demais", null,
                List.of(png(10, 10), png(10, 10), png(10, 10), png(10, 10), png(10, 10), png(10, 10)));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json()).containsEntry("codigo", "imagens_demais");
        assertThat(storage().count(BUCKET)).isEqualTo(before);
    }

    @Test
    void aGifMustBeAlone() {
        TestUser ana = newUser();
        long before = storage().count(BUCKET);
        Http.Response mixed = createWithImages(ana, "misturado", null, List.of(png(10, 10), gif(10, 10, 2)));
        assertThat(mixed.status()).isEqualTo(422);
        assertThat(mixed.json()).containsEntry("codigo", "gif_deve_ficar_sozinho");
        assertThat(storage().count(BUCKET)).isEqualTo(before);

        Http.Response alone = createWithImages(ana, "só o gif", null, List.of(gif(10, 10, 2)));
        assertThat(alone.status()).as(alone.body()).isEqualTo(201);
        assertThat(images(alone)).hasSize(1);
        assertThat(images(alone).get(0)).containsEntry("tipo", "GIF");
    }

    @Test
    void anInvalidImageInTheSetStoresNothing() {
        TestUser ana = newUser();
        long before = storage().count(BUCKET);
        Http.Response response = createWithImages(ana, "uma quebrada", null,
                List.of(png(10, 10), "<html>não é imagem</html>".getBytes(StandardCharsets.UTF_8)));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json()).containsEntry("codigo", "imagem_invalida");
        assertThat(storage().count(BUCKET)).isEqualTo(before);
    }

    @Test
    void repliesAcceptSeveralImagesAndDeletingThePostRemovesAllObjects() {
        TestUser ana = newUser();
        String root = post(ana, "raiz");
        Http.Response reply = createWithImages(ana, null, root, List.of(png(20, 20), png(30, 30)));
        assertThat(reply.status()).as(reply.body()).isEqualTo(201);
        List<String> keys = images(reply).stream().map(i -> keyOf(i.get("url"))).toList();
        assertThat(keys).hasSize(2);
        keys.forEach(k -> assertThat(storage().exists(BUCKET, k)).isTrue());

        assertThat(http().send("DELETE", "/api/postagens/" + reply.json().get("id"), null, ana.token).status())
                .isEqualTo(204);
        keys.forEach(k -> assertThat(storage().exists(BUCKET, k)).isFalse());
    }

    @Test
    void theLegacySingleImageFieldStillWorks() {
        TestUser ana = newUser();
        Http.Response created = createPost(ana, "uma só", null, png(40, 40));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(images(created)).hasSize(1);
    }
}
