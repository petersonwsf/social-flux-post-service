package com.socialflux.post;

import com.socialflux.post.service.PostMediaService;
import com.socialflux.post.support.AbstractPostTest;
import com.socialflux.post.support.Http;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Anexos (imagem ou GIF) no POST, entrega autenticada por visibilidade, ETag/304 e limpeza de objetos (Fase 5). */
class MediaTest extends AbstractPostTest {

    @Autowired
    private PostMediaService media;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> image(Http.Response response) {
        return (Map<String, Object>) response.json().get("imagem");
    }

    private static String keyOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    @Test
    void imagesAreReEncodedStoredAndServedOnlyToWhoCanSeeThePost() {
        TestUser ana = newUser();
        Http.Response created = createPost(ana, "foto", null, png(2400, 1200));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        Map<String, Object> image = image(created);
        assertThat(image).containsEntry("tipo", "IMAGE").containsEntry("largura", 1600).containsEntry("altura", 800);
        String url = (String) image.get("url");
        assertThat(url).matches("^/media/posts/po_[0-9a-f-]{36}\\.jpg$");
        assertThat(storage().exists(BUCKET, keyOf(url))).isTrue();

        Http.Response served = get(newUser(), url);
        assertThat(served.status()).isEqualTo(200);
        assertThat(served.header("Content-Type")).isEqualTo("image/jpeg");
        assertThat(served.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(served.header("Cache-Control")).contains("private").contains("immutable").doesNotContain("public");
        assertThat(served.header("ETag")).isEqualTo("\"" + keyOf(url) + "\"");
        assertThat(served.bytes()[0] & 0xFF).isEqualTo(0xFF);   // JPEG novo, o PNG original não é guardado

        Http.Response revalidated = http().send("GET", url, null, ana.token, Map.of("If-None-Match", served.header("ETag")));
        assertThat(revalidated.status()).isEqualTo(304);
        assertThat(revalidated.bytes()).isEmpty();
    }

    @Test
    void revalidationStillChecksVisibility() {
        TestUser ana = newUser();
        TestUser viewer = newUser();
        String url = (String) image(createPost(ana, "foto", null, png(50, 50))).get("url");
        String etag = get(viewer, url).header("ETag");
        setPrivate(ana, true);
        assertThat(http().send("GET", url, null, viewer.token, Map.of("If-None-Match", etag)).status()).isEqualTo(404);
    }

    @Test
    void gifsArriveIntactAndAnimated() throws Exception {
        TestUser ana = newUser();
        byte[] gif = gif(64, 48, 5);
        Http.Response created = createPost(ana, null, null, concat(gif, "lixo depois do fim".getBytes(StandardCharsets.UTF_8)));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        Map<String, Object> image = image(created);
        assertThat(image).containsEntry("tipo", "GIF").containsEntry("largura", 64).containsEntry("altura", 48);
        String url = (String) image.get("url");
        assertThat(url).endsWith(".gif");

        Http.Response served = get(ana, url);
        assertThat(served.header("Content-Type")).isEqualTo("image/gif");
        assertThat(served.bytes()).isEqualTo(gif);   // regravado igual (o lixo após o fim foi descartado)
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(served.bytes()))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(in);
            assertThat(reader.getNumImages(true)).isEqualTo(5);
            reader.dispose();
        }
    }

    @Test
    void invalidUploadsAreRefusedWithStableCodes() {
        TestUser ana = newUser();
        int before = storage().count(BUCKET);
        Http.Response fake = createPost(ana, "x", null, "<html>não é imagem</html>".getBytes(StandardCharsets.UTF_8));
        assertThat(fake.status()).isEqualTo(422);
        assertThat(fake.json()).containsEntry("codigo", "imagem_invalida");

        byte[] brokenGif = gif(10, 10, 2);
        brokenGif[brokenGif.length - 2] = 0x55;   // estrutura quebrada perto do fim
        assertThat(createPost(ana, "x", null, java.util.Arrays.copyOf(brokenGif, brokenGif.length - 1)).json())
                .containsEntry("codigo", "gif_invalido");

        Http.Response huge = createPost(ana, "x", null, new byte[9 * 1024 * 1024]);
        assertThat(huge.status()).isEqualTo(413);
        assertThat(huge.json()).containsEntry("codigo", "imagem_muito_grande");

        Http.Response bomb = createPost(ana, "x", null, png(5000, 5000));   // 25 MP > 16 MP
        assertThat(bomb.status()).isEqualTo(413);
        assertThat(storage().count(BUCKET)).as("nada gravado nas recusas").isEqualTo(before);
    }

    @Test
    void failedTransactionRemovesTheStoredObject() {
        TestUser ana = newUser();
        int before = storage().count(BUCKET);
        // o pai some entre a checagem barata e a transação? Simula com um pai inexistente: recusa ANTES de gravar a imagem
        assertThat(createPost(ana, "x", UUID.randomUUID().toString(), png(10, 10)).status()).isEqualTo(404);
        assertThat(storage().count(BUCKET)).isEqualTo(before);
    }

    @Test
    void deletingThePostDeletesTheObjectsOfTheWholeTree() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        Http.Response root = createPost(ana, "raiz", null, png(10, 10));
        String rootId = (String) root.json().get("id");
        Http.Response child = createPost(bia, null, rootId, gif(4, 4, 1));
        String rootKey = keyOf((String) image(root).get("url"));
        String childKey = keyOf((String) image(child).get("url"));

        assertThat(delete(ana, rootId).status()).isEqualTo(204);
        assertThat(storage().exists(BUCKET, rootKey)).isFalse();
        assertThat(storage().exists(BUCKET, childKey)).isFalse();
        assertThat(get(ana, "/media/posts/" + rootKey).status()).isEqualTo(404);
    }

    @Test
    void unknownOrMalformedKeysAre404() {
        TestUser ana = newUser();
        assertThat(get(ana, "/media/posts/po_" + UUID.randomUUID() + ".jpg").status()).isEqualTo(404);
        assertThat(get(ana, "/media/posts/av_" + UUID.randomUUID() + ".jpg").status()).isEqualTo(404);
        assertThat(get(ana, "/media/posts/..%2F..%2Fetc%2Fpasswd").status()).isIn(400, 404);
        assertThat(get(ana, "/media/posts/qualquer.svg").status()).isEqualTo(404);
    }

    @Test
    void orphanSweepRemovesOnlyOldObjectsWithoutPost() {
        TestUser ana = newUser();
        String kept = keyOf((String) image(createPost(ana, "fica", null, png(10, 10))).get("url"));
        Instant old = Instant.now().minus(2, ChronoUnit.HOURS);
        String oldOrphan = "po_" + UUID.randomUUID() + ".jpg";
        String newOrphan = "po_" + UUID.randomUUID() + ".jpg";
        storage().putAt(BUCKET, oldOrphan, new byte[]{1}, "image/jpeg", old);
        storage().putAt(BUCKET, newOrphan, new byte[]{1}, "image/jpeg", Instant.now());
        storage().putAt(BUCKET, kept, storage().get(BUCKET, kept).orElseThrow().bytes(), "image/jpeg", old);

        assertThat(media.sweepOrphans()).isEqualTo(1);
        assertThat(storage().exists(BUCKET, oldOrphan)).isFalse();
        assertThat(storage().exists(BUCKET, newOrphan)).as("dentro da carência").isTrue();
        assertThat(storage().exists(BUCKET, kept)).as("tem postagem").isTrue();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
