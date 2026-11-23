package com.socialflux.post;

import com.socialflux.post.support.AbstractPostTest;
import com.socialflux.post.support.Http;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Paginação por cursor (created_at, id): estável com empates de data, filtros e parâmetros validados. */
class PaginationTest extends AbstractPostTest {

    private List<Object> walk(TestUser viewer, String basePath, int pageSize) {
        List<Object> all = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 50; i++) {
            String sep = basePath.contains("?") ? "&" : "?";
            Http.Response page = get(viewer, basePath + sep + "limite=" + pageSize + (cursor == null ? "" : "&cursor=" + cursor));
            all.addAll(ids(page));
            cursor = (String) page.json().get("proximoCursor");
            if (cursor == null) {
                return all;
            }
        }
        throw new AssertionError("paginação não terminou");
    }

    @Test
    void feedPagesAreStableEvenWithIdenticalTimestamps() {
        TestUser viewer = newUser();
        TestUser a = newUser();
        TestUser b = newUser();
        follow(viewer, a);
        follow(viewer, b);
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            created.add(post(i % 2 == 0 ? a : b, "post " + i));
        }
        // empate: todas com a MESMA data, a ordem cai no id
        jdbc.sql("update post set created_at = '2026-11-15T12:00:00Z' where author_id in (:a, :b)")
                .param("a", a.id).param("b", b.id).update();

        List<Object> walked = walk(viewer, "/api/postagens/feed", 2);
        assertThat(walked).hasSize(7).doesNotHaveDuplicates();
        assertThat(walked).containsExactlyElementsOf(created.stream().sorted((x, y) ->
                UUID.fromString(y).compareTo(UUID.fromString(x))).map(Object.class::cast).toList());
    }

    @Test
    void newPostsDoNotShiftThePagesAlreadyServed() {
        TestUser viewer = newUser();
        TestUser a = newUser();
        follow(viewer, a);
        for (int i = 0; i < 4; i++) {
            post(a, "antigo " + i);
        }
        Http.Response first = get(viewer, "/api/postagens/feed?limite=2");
        String cursor = (String) first.json().get("proximoCursor");
        post(a, "novo no meio da leitura");
        List<Object> second = ids(get(viewer, "/api/postagens/feed?limite=2&cursor=" + cursor));
        assertThat(second).hasSize(2).doesNotContainAnyElementsOf(ids(first));
    }

    @Test
    void authorFiltersSelectPostsInteractionsAndMedia() {
        TestUser ana = newUser();
        TestUser other = newUser();
        String text = post(ana, "texto");
        String media = (String) createPost(ana, "foto", null, png(10, 10)).json().get("id");
        String othersPost = post(other, "de outra pessoa");
        String myReply = reply(ana, othersPost, "comentei");

        String base = "/api/postagens?autor=" + ana.id;
        assertThat(ids(get(other, base))).containsExactly(media, text);
        assertThat(ids(get(other, base + "&filtro=posts"))).containsExactly(media, text);
        assertThat(ids(get(other, base + "&filtro=interacoes"))).containsExactly(myReply, media, text);
        assertThat(ids(get(other, base + "&filtro=midia"))).containsExactly(media);
        assertThat(walk(other, base + "&filtro=interacoes", 1)).containsExactly(myReply, media, text);
    }

    @Test
    void invalidParametersAreRejected() {
        TestUser ana = newUser();
        for (String path : List.of("/api/postagens/feed?limite=0", "/api/postagens/feed?limite=101",
                "/api/postagens/feed?limite=abc", "/api/postagens/feed?cursor=nao-e-cursor",
                "/api/postagens?autor=" + ana.id + "&filtro=tudo", "/api/postagens?autor=xyz")) {
            Http.Response response = get(ana, path);
            assertThat(response.status()).as(path).isEqualTo(400);
            assertThat(response.json()).as(path).containsEntry("codigo", "parametro_invalido");
        }
        assertThat(get(ana, "/api/postagens/feed?limite=100").status()).isEqualTo(200);
    }
}
