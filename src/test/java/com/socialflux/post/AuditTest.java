package com.socialflux.post;

import com.socialflux.audit.AuditMode;
import com.socialflux.audit.InMemoryAuditRecorder;
import com.socialflux.audit.InMemoryAuditRecorder.Recorded;
import com.socialflux.post.support.AbstractPostTest;
import com.socialflux.post.support.Http;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Auditoria central: recusas da moderação e exclusões (recorder em memória no lugar do audit-service). */
@Import(ContentModerationTest.FakeModeratorConfig.class)
class AuditTest extends AbstractPostTest {

    @Autowired
    InMemoryAuditRecorder audit;

    @BeforeEach
    void clear() {
        audit.clear();
    }

    @Test
    void refusedTextIsAuditedWithCategoryAndWithoutTheText() {
        TestUser ana = newUser();

        Http.Response response = createPost(ana, "texto secreto [odio]");
        assertThat(response.status()).isEqualTo(422);

        List<Recorded> refused = audit.recorded("CONTENT_REFUSED");
        assertThat(refused).hasSize(1);
        Recorded item = refused.get(0);
        assertThat(item.mode()).isEqualTo(AuditMode.INDEPENDENT);
        assertThat(item.payload()).containsEntry("outcome", "DENIED").containsEntry("service", "post")
                .containsEntry("actorId", ana.id.toString());
        assertThat(item.payload().get("detail").toString())
                .contains("contentType=TEXT", "category=hate", "verdict=FORBIDDEN")
                .doesNotContain("secreto").doesNotContain("[odio]");
    }

    @Test
    void refusedImageIsAuditedAsImage() {
        TestUser ana = newUser();

        Http.Response response = createPost(ana, null, null, png(33, 20));   // largura 33 = proibida no moderador falso
        assertThat(response.status()).isEqualTo(422);

        assertThat(audit.recorded("CONTENT_REFUSED")).singleElement().satisfies(item ->
                assertThat(item.payload().get("detail").toString()).contains("contentType=IMAGE", "category=sexual"));
    }

    @Test
    void publishedContentIsNotAudited() {
        TestUser ana = newUser();
        assertThat(createPost(ana, "tudo bem por aqui").status()).isEqualTo(201);
        assertThat(audit.recorded("CONTENT_REFUSED")).isEmpty();
    }

    @Test
    void postAndReplyDeletionsSayWhoDeleted() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        String root = post(ana, "postagem");
        String child = reply(bia, root, "resposta");

        assertThat(delete(bia, child).status()).isEqualTo(204);
        assertThat(audit.recorded("REPLY_DELETED")).singleElement().satisfies(item -> {
            assertThat(item.mode()).isEqualTo(AuditMode.IN_TRANSACTION);
            assertThat(item.payload()).containsEntry("actorId", bia.id.toString()).containsEntry("targetId", child)
                    .containsEntry("targetType", "POST");
            assertThat(item.payload().get("detail").toString()).contains("deletedBy=AUTHOR");
        });

        assertThat(delete(ana, root).status()).isEqualTo(204);
        assertThat(audit.recorded("POST_DELETED")).singleElement()
                .satisfies(item -> assertThat(item.payload()).containsEntry("targetId", root));

        // Quem não é o autor não apaga, e a tentativa recusada não gera evento de exclusão.
        String other = post(ana, "outra");
        audit.clear();
        assertThat(delete(bia, other).status()).isEqualTo(403);
        assertThat(audit.recorded("POST_DELETED")).isEmpty();
    }
}
