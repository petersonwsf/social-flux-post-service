package com.socialflux.post;

import com.socialflux.post.support.AbstractPostTest;
import com.socialflux.post.support.Http;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Criar, ler e excluir postagens e respostas (regras de conteúdo, IDOR e cascata). */
class PostFlowTest extends AbstractPostTest {

    @Test
    void createsATextPostWithTheFullContract() {
        TestUser ana = newUser();
        Http.Response response = createPost(ana, "  meu primeiro post  ");

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        Map<String, Object> post = response.json();
        assertThat(UUID.fromString((String) post.get("id")).version()).isEqualTo(7);
        assertThat(post).containsEntry("legenda", "meu primeiro post").containsEntry("imagem", null)
                .containsEntry("pai", null).containsEntry("curtidas", 0).containsEntry("curtido", false)
                .containsEntry("respostas", 0);
        assertThat(Instant.parse((String) post.get("criado_em"))).isBefore(Instant.now().plusSeconds(1));
        @SuppressWarnings("unchecked")
        Map<String, Object> author = (Map<String, Object>) post.get("autor");
        assertThat(author).containsEntry("id", ana.id.toString()).doesNotContainKey("accountId")
                .containsEntry("username", ana.username).containsEntry("privado", false);
        assertThat(get(ana, "/api/postagens/" + post.get("id")).json()).containsEntry("legenda", "meu primeiro post");
    }

    @Test
    void enforcesContentRules() {
        TestUser ana = newUser();
        assertThat(createPost(ana, null).json()).containsEntry("codigo", "postagem_vazia");
        assertThat(createPost(ana, "   \n\t ").status()).isEqualTo(422);
        Http.Response tooLong = createPost(ana, "a".repeat(1001));
        assertThat(tooLong.status()).isEqualTo(422);
        assertThat(tooLong.json()).containsEntry("codigo", "texto_muito_longo");
        assertThat(createPost(ana, "a".repeat(1000)).status()).isEqualTo(201);
        assertThat(createPost(ana, "😀".repeat(1000)).status()).as("conta caracteres, não bytes").isEqualTo(201);
        assertThat(createPost(ana, "x", "nao-e-uuid", null).json()).containsEntry("codigo", "parametro_invalido");
    }

    @Test
    void imageOnlyPostsAreAccepted() {   // LOG-08: o legado recusava imagem sem legenda
        TestUser ana = newUser();
        Http.Response response = createPost(ana, null, null, png(40, 30));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(response.json()).containsEntry("legenda", null);
    }

    @Test
    void unknownAuthorCannotPublishYet() {
        Http.Response response = createPost(unknownUser(), "oi");
        assertThat(response.status()).isEqualTo(503);
        assertThat(response.json()).containsEntry("codigo", "perfil_indisponivel");
    }

    @Test
    void repliesCountAndPointToTheirParent() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        String root = post(ana, "raiz");
        String r1 = reply(bia, root, "resposta");

        Map<String, Object> reply = get(ana, "/api/postagens/" + r1).json();
        assertThat(reply.get("pai")).isEqualTo(Map.of("id", root, "autor_username", ana.username));
        assertThat(get(ana, "/api/postagens/" + root).json()).containsEntry("respostas", 1);
        assertThat(ids(get(ana, "/api/postagens/" + root + "/respostas"))).containsExactly(r1);
    }

    @Test
    void replyingToAMissingOrInvisibleParentLooksTheSame() {
        TestUser priv = newUser(true);
        TestUser stranger = newUser();
        String hidden = post(priv, "só para seguidores");

        Http.Response missing = createPost(stranger, "oi", UUID.randomUUID().toString(), null);
        Http.Response invisible = createPost(stranger, "oi", hidden, null);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(invisible.status()).isEqualTo(404);
        assertThat(invisible.json()).isEqualTo(missing.json());
        assertThat(get(priv, "/api/postagens/" + hidden).json()).containsEntry("respostas", 0);
    }

    @Test
    void onlyTheAuthorDeletesAndTheTreeGoesWithIt() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        String root = post(ana, "raiz");
        String child = reply(bia, root, "filho");
        String grandchild = reply(ana, child, "neto");
        like(bia, grandchild);

        Http.Response notMine = delete(bia, root);
        assertThat(notMine.status()).isEqualTo(403);
        assertThat(notMine.json()).containsEntry("codigo", "forbidden");
        assertThat(delete(bia, UUID.randomUUID().toString()).status()).isEqualTo(404);
        assertThat(delete(bia, "lixo").status()).isEqualTo(404);

        assertThat(delete(bia, child).status()).isEqualTo(204);           // bia exclui a SUA resposta: some o neto junto
        assertThat(get(ana, "/api/postagens/" + grandchild).status()).isEqualTo(404);
        assertThat(get(ana, "/api/postagens/" + root).json()).containsEntry("respostas", 0);
        assertThat(count("select count(*) from post_like where post_id = :id", UUID.fromString(grandchild))).isZero();

        assertThat(delete(ana, root).status()).isEqualTo(204);
        assertThat(get(ana, "/api/postagens/" + root).status()).isEqualTo(404);
        assertThat(delete(ana, root).status()).isEqualTo(404);
        assertCountersConsistent();
    }

    @Test
    void anyAuthorCanDeleteTheirOwnPost() {
        for (int i = 0; i < 3; i++) {
            TestUser user = newUser();
            assertThat(delete(user, post(user, "post " + i)).status()).isEqualTo(204);
        }
    }

    @Test
    void deletingStillWorksWhenTheImageFileIsGone() {   // LOG-05
        TestUser ana = newUser();
        Map<String, Object> created = createPost(ana, "com foto", null, png(30, 30)).json();
        @SuppressWarnings("unchecked")
        String url = (String) ((Map<String, Object>) created.get("imagem")).get("url");
        storage().delete(BUCKET, url.substring(url.lastIndexOf('/') + 1));   // o arquivo sumiu do armazenamento
        assertThat(delete(ana, (String) created.get("id")).status()).isEqualTo(204);
    }

    @Test
    void ancestorsComeFromTheOldestToTheClosest() {
        TestUser ana = newUser();
        String a = post(ana, "a");
        String b = reply(ana, a, "b");
        String c = reply(ana, b, "c");
        String d = reply(ana, c, "d");
        assertThat(ids(get(ana, "/api/postagens/" + d + "/ancestrais"))).containsExactly(a, b, c);
        assertThat(ids(get(ana, "/api/postagens/" + a + "/ancestrais"))).isEmpty();
    }

    @Test
    void ancestorsStopAtTenLevels() {
        TestUser ana = newUser();
        String current = post(ana, "0");
        for (int i = 1; i <= 12; i++) {
            current = reply(ana, current, String.valueOf(i));
        }
        assertThat(items(get(ana, "/api/postagens/" + current + "/ancestrais"))).hasSize(10)
                .first().extracting(p -> p.get("legenda")).isEqualTo("2");
    }
}
