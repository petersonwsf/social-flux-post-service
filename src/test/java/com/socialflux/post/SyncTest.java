package com.socialflux.post;

import com.socialflux.post.support.AbstractPostTest;
import com.socialflux.post.support.Http;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Cópias locais alimentadas por eventos: perfis ({@code profile.*}) e arestas ({@code follow.*}), inclusive fora de ordem. */
class SyncTest extends AbstractPostTest {

    private long edges(TestUser follower, TestUser followee) {
        return jdbc.sql("select count(*) from follow_edge where follower_id = :f and followee_id = :t")
                .param("f", follower.id).param("t", followee.id).query(Long.class).single();
    }

    @Test
    void duplicatedStaleAndUnknownProfileEventsAreHarmless() {
        TestUser ana = newUser();
        String created = profileEventJson(UUID.randomUUID(), "profile.created.v1", ana);
        events.handleProfile(created);
        events.handleProfile(created);

        ana.version = 5;
        ana.isPrivate = true;
        deliverProfile("profile.updated.v1", ana);
        ana.version = 3;
        ana.isPrivate = false;
        deliverProfile("profile.updated.v1", ana);       // antigo: ignorado
        ana.version = 6;
        deliverProfile("profile.algo-novo.v1", ana);     // desconhecido: ignorado

        assertThat(jdbc.sql("select is_private from author_snapshot where user_id = :id").param("id", ana.id)
                .query(Boolean.class).single()).isTrue();
        assertThat(count("select profile_version from author_snapshot where user_id = :id", ana.id)).isEqualTo(5);
    }

    @Test
    void deletionBeforeCreationLeavesATombstoneThatALateCreateCannotRevive() {
        TestUser ghost = unknownUser();
        ghost.version = 2;
        deliverProfile("profile.deleted.v1", ghost);
        ghost.version = 1;
        deliverProfile("profile.created.v1", ghost);

        assertThat(jdbc.sql("select status from author_snapshot where user_id = :id").param("id", ghost.id)
                .query(String.class).single()).isEqualTo("DELETED");
        assertThat(createPost(ghost, "voltei?").status()).isEqualTo(503);
    }

    @Test
    void followEventsBuildEdgesAndToleratesDuplicates() {
        TestUser a = newUser();
        TestUser b = newUser();
        UUID relation = follow(a, b);
        String duplicate = followEventJson(UUID.randomUUID(), "follow.created.v1", relation, a.id, b.id, Instant.now());
        events.handleFollow(duplicate);
        events.handleFollow(duplicate);
        assertThat(edges(a, b)).isEqualTo(1);

        unfollow(a, b);
        assertThat(edges(a, b)).isZero();
        events.handleFollow(followEventJson(UUID.randomUUID(), "follow.requested.v1", UUID.randomUUID(), a.id, b.id,
                Instant.now()));   // solicitação: não é aresta
        assertThat(edges(a, b)).isZero();
    }

    @Test
    void removedArrivingBeforeCreatedNeverCreatesTheEdge() {
        TestUser a = newUser();
        TestUser b = newUser();
        UUID relation = UUID.randomUUID();
        events.handleFollow(followEventJson(UUID.randomUUID(), "follow.removed.v1", relation, a.id, b.id, null));
        events.handleFollow(followEventJson(UUID.randomUUID(), "follow.created.v1", relation, a.id, b.id, Instant.now()));
        assertThat(edges(a, b)).isZero();
    }

    @Test
    void aStaleRemovalDoesNotBreakANewerRelation() {
        TestUser a = newUser();
        TestUser b = newUser();
        UUID old = follow(a, b);
        unfollow(a, b);
        UUID current = follow(a, b);
        // remoção do vínculo ANTIGO entregue de novo (atrasada): não derruba o vínculo novo
        events.handleFollow(followEventJson(UUID.randomUUID(), "follow.removed.v1", old, a.id, b.id, null));
        assertThat(jdbc.sql("select relation_id from follow_edge where follower_id = :f and followee_id = :t")
                .param("f", a.id).param("t", b.id).query(UUID.class).single()).isEqualTo(current);
        // um created antigo (data menor) de outro vínculo não substitui a aresta mais nova
        events.handleFollow(followEventJson(UUID.randomUUID(), "follow.created.v1", UUID.randomUUID(), a.id, b.id,
                Instant.parse("2020-01-01T00:00:00Z")));
        assertThat(jdbc.sql("select relation_id from follow_edge where follower_id = :f and followee_id = :t")
                .param("f", a.id).param("t", b.id).query(UUID.class).single()).isEqualTo(current);
    }

    @Test
    void accountDeletionErasesPostsDependentRepliesLikesEdgesAndMedia() {
        TestUser gone = newUser();
        TestUser other = newUser();
        follow(other, gone);
        follow(gone, other);
        String root = post(gone, "vou sumir");
        String replyByOther = reply(other, root, "resposta de outra pessoa");
        String otherPost = post(other, "fica");
        String goneReplyOnOther = reply(gone, otherPost, "comentário que some");
        Http.Response imagePost = createPost(gone, null, null, png(20, 20));
        String withImage = (String) imagePost.json().get("id");
        @SuppressWarnings("unchecked")
        String url = (String) ((java.util.Map<String, Object>) imagePost.json().get("imagem")).get("url");
        String key = url.substring(url.lastIndexOf('/') + 1);
        like(gone, otherPost);
        like(other, root);
        assertThat(storage().exists(BUCKET, key)).isTrue();

        deleteProfile(gone);

        for (String id : new String[]{root, replyByOther, goneReplyOnOther, withImage}) {
            assertThat(count("select count(*) from post where id = :id", UUID.fromString(id))).as(id).isZero();
        }
        assertThat(get(other, "/api/postagens/" + otherPost).json())
                .containsEntry("curtidas", 0).containsEntry("respostas", 0);
        assertThat(count("select count(*) from post_like where user_id = :id", gone.id)).isZero();
        assertThat(count("select count(*) from follow_edge where follower_id = :id or followee_id = :id", gone.id)).isZero();
        assertThat(storage().exists(BUCKET, key)).as("a mídia da conta é apagada").isFalse();
        assertThat(get(other, "/api/postagens?autor=" + gone.id).status()).isEqualTo(404);
        assertCountersConsistent();
        assertThat(eventTypesMentioning(root)).contains("post.deleted.v1");
        assertThat(eventTypesMentioning(replyByOther)).contains("post.deleted.v1");
    }
}
