package com.socialflux.post;

import com.socialflux.post.support.AbstractPostTest;
import com.socialflux.post.support.Http;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matriz de visibilidade (D6) em cada rota: dono, autor público, conta privada + seguidor, conta privada + pedido pendente
 * (para este serviço, igual a estranho: só aresta aceita conta), conta privada + estranho e conta excluída.
 */
class VisibilityTest extends AbstractPostTest {

    private record Scenario(TestUser owner, TestUser follower, TestUser stranger, String post, String imageUrl) {
    }

    private Scenario privateAccount() {
        TestUser owner = newUser(true);
        TestUser follower = newUser();
        TestUser stranger = newUser();   // inclui quem só tem pedido pendente: o grafo não publica aresta para pedido
        follow(follower, owner);
        Map<String, Object> created = createPost(owner, "privado", null, png(20, 20)).json();
        @SuppressWarnings("unchecked")
        String url = (String) ((Map<String, Object>) created.get("imagem")).get("url");
        return new Scenario(owner, follower, stranger, (String) created.get("id"), url);
    }

    @Test
    void singlePostRespectsPrivacy() {
        Scenario s = privateAccount();
        assertThat(get(s.owner(), "/api/postagens/" + s.post()).status()).isEqualTo(200);
        assertThat(get(s.follower(), "/api/postagens/" + s.post()).status()).isEqualTo(200);
        Http.Response hidden = get(s.stranger(), "/api/postagens/" + s.post());
        assertThat(hidden.status()).isEqualTo(404);
        assertThat(hidden.json()).containsEntry("codigo", "postagem_nao_encontrada");

        TestUser pub = newUser();
        assertThat(get(s.stranger(), "/api/postagens/" + post(pub, "público")).status()).isEqualTo(200);
    }

    @Test
    void profileListsOfPrivateAccountsAreForbiddenToStrangers() {
        Scenario s = privateAccount();
        String path = "/api/postagens?autor=" + s.owner().id;
        assertThat(ids(get(s.owner(), path))).containsExactly(s.post());
        assertThat(ids(get(s.follower(), path))).containsExactly(s.post());
        Http.Response forbidden = get(s.stranger(), path);
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.json()).containsEntry("codigo", "conta_privada");
        assertThat(get(s.stranger(), "/api/postagens?autor=999999999").status()).isEqualTo(400);   // id inteiro: só UUID
        assertThat(get(s.stranger(), "/api/postagens").status()).isEqualTo(400);
    }

    @Test
    void feedOnlyHasRootPostsOfFollowedAccounts() {
        Scenario s = privateAccount();
        TestUser pub = newUser();
        String pubPost = post(pub, "público, mas não sigo");
        reply(s.owner(), s.post(), "resposta não vai ao feed");

        assertThat(ids(get(s.follower(), "/api/postagens/feed"))).containsExactly(s.post());
        assertThat(ids(get(s.stranger(), "/api/postagens/feed"))).isEmpty();
        follow(s.stranger(), pub);
        assertThat(ids(get(s.stranger(), "/api/postagens/feed"))).containsExactly(pubPost);
    }

    @Test
    void repliesAreFilteredByTheirOwnAuthor() {
        TestUser pub = newUser();
        TestUser priv = newUser(true);
        TestUser viewer = newUser();
        String root = post(pub, "público");
        String visibleReply = reply(newUser(), root, "resposta pública");
        String hiddenReply = reply(priv, root, "resposta de conta privada");

        assertThat(ids(get(viewer, "/api/postagens/" + root + "/respostas"))).containsExactly(visibleReply);
        assertThat(get(viewer, "/api/postagens/" + hiddenReply).status()).isEqualTo(404);
        follow(viewer, priv);
        assertThat(ids(get(viewer, "/api/postagens/" + root + "/respostas"))).containsExactly(hiddenReply, visibleReply);
    }

    @Test
    void repliesOfAnInvisiblePostAreHiddenToo() {
        Scenario s = privateAccount();
        assertThat(get(s.stranger(), "/api/postagens/" + s.post() + "/respostas").status()).isEqualTo(404);
        assertThat(get(s.stranger(), "/api/postagens/" + s.post() + "/ancestrais").status()).isEqualTo(404);
    }

    @Test
    void ancestorsStopAtTheFirstInvisibleLevelAndParentSummaryHidesTheUsername() {
        TestUser pub = newUser();
        TestUser priv = newUser(true);
        TestUser viewer = newUser();
        follow(pub, priv);
        String top = post(pub, "topo público");
        String middle = reply(priv, top, "meio privado");
        String leaf = reply(pub, middle, "folha pública");

        assertThat(ids(get(viewer, "/api/postagens/" + leaf + "/ancestrais"))).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> parent = (Map<String, Object>) get(viewer, "/api/postagens/" + leaf).json().get("pai");
        assertThat(parent).containsEntry("id", middle).containsEntry("autor_username", null);
        assertThat(ids(get(pub, "/api/postagens/" + leaf + "/ancestrais"))).containsExactly(top, middle);
    }

    @Test
    void likingAndMediaFollowVisibility() {
        Scenario s = privateAccount();
        assertThat(like(s.stranger(), s.post()).status()).isEqualTo(404);
        assertThat(unlike(s.stranger(), s.post()).status()).isEqualTo(404);
        assertThat(like(s.follower(), s.post()).status()).isEqualTo(200);
        assertThat(get(s.owner(), s.imageUrl()).status()).isEqualTo(200);
        assertThat(get(s.follower(), s.imageUrl()).status()).isEqualTo(200);
        assertThat(get(s.stranger(), s.imageUrl()).status()).isEqualTo(404);
    }

    @Test
    void privacyChangesAndUnfollowsApplyOnTheNextRequest() {
        TestUser ana = newUser();
        TestUser fan = newUser();
        TestUser stranger = newUser();
        follow(fan, ana);
        String id = post(ana, "era público");
        assertThat(get(stranger, "/api/postagens/" + id).status()).isEqualTo(200);

        setPrivate(ana, true);
        assertThat(get(stranger, "/api/postagens/" + id).status()).isEqualTo(404);
        assertThat(get(fan, "/api/postagens/" + id).status()).isEqualTo(200);

        unfollow(fan, ana);
        assertThat(get(fan, "/api/postagens/" + id).status()).isEqualTo(404);
        assertThat(ids(get(fan, "/api/postagens/feed"))).isEmpty();

        setPrivate(ana, false);
        assertThat(get(stranger, "/api/postagens/" + id).status()).isEqualTo(200);
    }

    @Test
    void deletedAccountsDisappearEverywhere() {
        TestUser gone = newUser();
        TestUser viewer = newUser();
        follow(viewer, gone);
        String id = post(gone, "adeus");
        deleteProfile(gone);
        assertThat(get(viewer, "/api/postagens/" + id).status()).isEqualTo(404);
        assertThat(ids(get(viewer, "/api/postagens/feed"))).isEmpty();
        assertThat(get(viewer, "/api/postagens?autor=" + gone.id).status()).isEqualTo(404);
    }
}
