package com.socialflux.post;

import com.socialflux.post.service.CounterReconciler;
import com.socialflux.post.service.Maintenance;
import com.socialflux.post.support.AbstractPostTest;
import com.socialflux.post.support.TestSupportConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Eventos post.* (outbox), broker fora do ar, reconciliador de contadores e rotinas de manutenção (Fase 6). */
class EventsTest extends AbstractPostTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private CounterReconciler reconciler;

    @Autowired
    private Maintenance maintenance;

    private JsonNode lastEvent(String type, Object id) {
        String payload = jdbc.sql("select payload from outbox_event where type = :type and aggregate_id = :id "
                        + "order by seq desc limit 1")
                .param("type", type).param("id", UUID.fromString(id.toString())).query(String.class).single();
        return MAPPER.readTree(payload);
    }

    @Test
    void postCreatedCarriesOnlyIdsDatesAndAShortExcerpt() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        String root = post(ana, "raiz");
        String longText = "x".repeat(300);
        String child = (String) createPost(bia, longText, root, gif(2, 2, 1)).json().get("id");

        JsonNode event = lastEvent("post.created.v1", child);
        assertThat(event.path("aggregateId").asString()).isEqualTo(child);
        assertThat(event.path("aggregateVersion").asLong()).isEqualTo(1);
        JsonNode data = event.path("data");
        assertThat(data.path("postId").asString()).isEqualTo(child);
        assertThat(data.path("authorId").asString()).isEqualTo(bia.id.toString());
        assertThat(data.path("parentId").asString()).isEqualTo(root);
        assertThat(data.path("parentAuthorId").asString()).isEqualTo(ana.id.toString());
        assertThat(data.path("mediaKind").asString()).isEqualTo("GIF");
        assertThat(data.path("excerpt").asString()).hasSize(120);
        assertThat(data.has("createdAt")).isTrue();
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("postId", "authorId", "parentId", "parentAuthorId",
                "mediaKind", "excerpt", "createdAt");
        assertThat(event.toString()).doesNotContain(bia.username).doesNotContain("Pessoa");

        JsonNode rootEvent = lastEvent("post.created.v1", root).path("data");
        assertThat(rootEvent.path("parentId").isNull()).isTrue();
        assertThat(rootEvent.path("mediaKind").isNull()).isTrue();
    }

    @Test
    void cascadeDeletionPublishesOneEventPerRemovedPost() {
        TestUser ana = newUser();
        String root = post(ana, "raiz");
        String child = reply(ana, root, "filho");
        String grandchild = reply(newUser(), child, "neto");
        delete(ana, root);
        for (String id : List.of(root, child, grandchild)) {
            assertThat(eventTypesMentioning(id)).as(id).endsWith("post.deleted.v1");
        }
        assertThat(lastEvent("post.deleted.v1", grandchild).path("data").propertyNames())
                .containsExactlyInAnyOrder("postId", "authorId");
    }

    @Test
    void likeEventsCarryThePostAuthorAndTheUser() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        String id = post(ana, "curta");
        like(bia, id);
        JsonNode data = lastEvent("post.liked.v1", id).path("data");
        assertThat(data.path("postAuthorId").asString()).isEqualTo(ana.id.toString());
        assertThat(data.path("userId").asString()).isEqualTo(bia.id.toString());
    }

    @Test
    void eventsSurviveTheBrokerBeingDown() {
        TestUser ana = newUser();
        drainOutbox();
        publisher.down = true;
        try {
            String id = post(ana, "com o broker fora");
            relay.publishPending();
            assertThat(publisher.sent).noneMatch(s -> s.json().contains(id));
            publisher.down = false;
            drainOutbox();
            assertThat(publisher.sent.stream().filter(s -> s.json().contains(id))
                    .map(TestSupportConfig.RecordingPublisher.Sent::routingKey)).containsExactly("post.created.v1");
        } finally {
            publisher.down = false;
        }
    }

    @Test
    void reconcilerFixesProvokedDivergence() {
        TestUser ana = newUser();
        String id = post(ana, "contadores");
        like(newUser(), id);
        reply(ana, id, "r");
        jdbc.sql("update post set likes_count = 42, replies_count = 0 where id = :id").param("id", UUID.fromString(id)).update();

        CounterReconciler.Result result = reconciler.reconcile();
        assertThat(result.fixed()).anySatisfy(check -> {
            assertThat(check.postId()).isEqualTo(UUID.fromString(id));
            assertThat(check.likesBefore()).isEqualTo(42);
            assertThat(check.likesAfter()).isEqualTo(1);
            assertThat(check.repliesAfter()).isEqualTo(1);
        });
        assertThat(get(ana, "/api/postagens/" + id).json()).containsEntry("curtidas", 1).containsEntry("respostas", 1);
        assertThat(reconciler.reconcile().fixedCount()).isZero();
        assertCountersConsistent();
    }

    @Test
    void maintenancePrunesOldTombstonesAndFinishesAccountPurges() {
        jdbc.sql("insert into follow_removed (relation_id, removed_at) values (:a, now() - interval '8 days'), "
                + "(:b, now())").param("a", UUID.randomUUID()).param("b", UUID.randomUUID()).update();
        assertThat(maintenance.pruneTombstones()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("select count(*) from follow_removed where removed_at < now() - interval '7 days'")
                .query(Long.class).single()).isZero();

        // conta marcada como excluída sem a limpeza ter rodado (por exemplo, o serviço caiu logo depois)
        TestUser gone = newUser();
        String id = post(gone, "sobrou");
        jdbc.sql("update author_snapshot set status = 'DELETED' where user_id = :id").param("id", gone.id).update();
        assertThat(maintenance.purgeDeletedAccounts()).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from post where id = :id", UUID.fromString(id))).isZero();
    }
}
