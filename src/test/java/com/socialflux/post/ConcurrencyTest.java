package com.socialflux.post;

import com.socialflux.post.support.AbstractPostTest;
import com.socialflux.post.support.Http;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Corridas: curtir × descurtir × excluir × responder mantêm os contadores certos e nunca dão 500. */
class ConcurrencyTest extends AbstractPostTest {

    private List<Integer> race(List<Callable<Http.Response>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(16, tasks.size()));
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Http.Response>> futures = new ArrayList<>();
            for (Callable<Http.Response> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Http.Response> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS).status());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelLikesAndUnlikesKeepTheCounterExact() throws Exception {
        TestUser ana = newUser();
        String id = post(ana, "disputada");
        List<TestUser> users = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            users.add(newUser());
        }
        List<Callable<Http.Response>> tasks = new ArrayList<>();
        for (TestUser user : users) {
            tasks.add(() -> like(user, id));
            tasks.add(() -> like(user, id));
            tasks.add(() -> unlike(user, id));
        }
        assertThat(race(tasks)).containsOnly(200);

        long likes = jdbc.sql("select count(*) from post_like where post_id = :id").param("id", UUID.fromString(id))
                .query(Long.class).single();
        assertThat(get(ana, "/api/postagens/" + id).json().get("curtidas")).isEqualTo((int) likes);
        assertCountersConsistent();
    }

    @Test
    void likingWhileThePostIsDeletedNeverBreaks() throws Exception {
        TestUser ana = newUser();
        for (int round = 0; round < 3; round++) {
            String id = post(ana, "round " + round);
            List<Callable<Http.Response>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                TestUser user = newUser();
                tasks.add(() -> like(user, id));
            }
            tasks.add(() -> delete(ana, id));
            assertThat(race(tasks)).allMatch(status -> status == 200 || status == 204 || status == 404);
            assertThat(get(ana, "/api/postagens/" + id).status()).isEqualTo(404);
        }
        assertCountersConsistent();
    }

    @Test
    void replyingWhileTheParentIsDeletedNeverLeavesOrphansOrWrongCounters() throws Exception {
        TestUser ana = newUser();
        TestUser bia = newUser();
        for (int round = 0; round < 3; round++) {
            String root = post(ana, "raiz " + round);
            String child = reply(ana, root, "filho");
            List<Callable<Http.Response>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> createPost(bia, "resposta", child, null));
                tasks.add(() -> createPost(bia, "resposta na raiz", root, null));
            }
            tasks.add(() -> delete(ana, root));
            assertThat(race(tasks)).allMatch(status -> status == 201 || status == 204 || status == 404);
            assertThat(jdbc.sql("select count(*) from post where parent_id is not null "
                            + "and parent_id not in (select id from post)").query(Long.class).single()).isZero();
        }
        assertCountersConsistent();
    }

    @Test
    void concurrentRepliesCountExactly() throws Exception {
        TestUser ana = newUser();
        String root = post(ana, "muitas respostas");
        List<Callable<Http.Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            TestUser user = newUser();
            tasks.add(() -> createPost(user, "r", root, null));
        }
        assertThat(race(tasks)).containsOnly(201);
        assertThat(get(ana, "/api/postagens/" + root).json()).containsEntry("respostas", 16);
        assertCountersConsistent();
    }

    @Test
    void deletingAChildAndItsAncestorAtTheSameTimeDoesNotDeadlock() throws Exception {
        TestUser ana = newUser();
        for (int round = 0; round < 3; round++) {
            String root = post(ana, "raiz");
            String child = reply(ana, root, "filho");
            String grandchild = reply(ana, child, "neto");
            List<Integer> statuses = race(List.of(() -> delete(ana, root), () -> delete(ana, grandchild),
                    () -> delete(ana, child)));
            assertThat(statuses).allMatch(status -> status == 204 || status == 404);
            assertThat(get(ana, "/api/postagens/" + root).status()).isEqualTo(404);
        }
        assertCountersConsistent();
    }
}
