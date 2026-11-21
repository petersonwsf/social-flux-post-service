package com.socialflux.post;

import com.socialflux.post.support.AbstractPostTest;
import com.socialflux.post.support.Http;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Curtidas idempotentes, sempre em nome de quem está no token, com contadores e "curti" embutidos na postagem. */
class LikeTest extends AbstractPostTest {

    @Test
    void likingIsIdempotentAndShowsUpInThePost() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser caio = newUser();
        String id = post(ana, "curta");

        Http.Response first = like(bia, id);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json()).containsEntry("curtido", true).containsEntry("curtidas", 1);
        assertThat(like(bia, id).json()).containsEntry("curtidas", 1);          // repetir não duplica
        assertThat(like(caio, id).json()).containsEntry("curtidas", 2);
        assertThat(like(ana, id).json()).containsEntry("curtidas", 3);          // pode curtir a própria

        assertThat(get(bia, "/api/postagens/" + id).json()).containsEntry("curtido", true).containsEntry("curtidas", 3);
        TestUser viewer = newUser();
        assertThat(get(viewer, "/api/postagens/" + id).json()).containsEntry("curtido", false);

        Http.Response undo = unlike(bia, id);
        assertThat(undo.json()).containsEntry("curtido", false).containsEntry("curtidas", 2);
        assertThat(unlike(bia, id).json()).containsEntry("curtidas", 2);        // descurtir sem curtida não faz nada
        assertThat(unlike(viewer, id).json()).containsEntry("curtidas", 2);
        assertCountersConsistent();
    }

    @Test
    void eventsOnlyWhenTheLikeActuallyChanged() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        String id = post(ana, "eventos");
        like(bia, id);
        like(bia, id);
        unlike(bia, id);
        unlike(bia, id);
        assertThat(eventTypesMentioning(id)).containsExactly("post.created.v1", "post.liked.v1", "post.unliked.v1");
    }

    @Test
    void likingAMissingPostIs404() {
        TestUser ana = newUser();
        assertThat(like(ana, "00000000-0000-7000-8000-000000000000").status()).isEqualTo(404);
        assertThat(like(ana, "lixo").status()).isEqualTo(404);
    }

    @Test
    void likesOfADeletedPostGoAway() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        String id = post(ana, "vai sumir");
        like(bia, id);
        delete(ana, id);
        assertThat(like(bia, id).status()).isEqualTo(404);
        assertThat(jdbc.sql("select count(*) from post_like where user_id = :id").param("id", bia.id)
                .query(Long.class).single()).isZero();
    }
}
