package com.socialflux.post.domain;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Cópia local das arestas de seguir ({@code follow_edge}) e dos túmulos de vínculos removidos ({@code follow_removed}).
 * Cada vínculo tem um {@code relationId} próprio: só vale a aresta mais nova do par, e um vínculo removido nunca volta.
 */
@Repository
public class FollowEdgeRepository {

    private final JdbcClient jdbc;

    public FollowEdgeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isRemoved(UUID relationId) {
        return jdbc.sql("select exists (select 1 from follow_removed where relation_id = :id)")
                .param("id", relationId).query(Boolean.class).single();
    }

    /** Grava a aresta do par; se já houver outra (de outro vínculo), troca só se esta for mais nova. */
    public void upsert(UUID relationId, UUID follower, UUID followee, OffsetDateTime createdAt) {
        jdbc.sql("""
                        insert into follow_edge (follower_id, followee_id, relation_id, created_at)
                        values (:follower, :followee, :relation, :at)
                        on conflict (follower_id, followee_id) do update
                           set relation_id = excluded.relation_id, created_at = excluded.created_at
                         where follow_edge.created_at < excluded.created_at""")
                .param("follower", follower).param("followee", followee).param("relation", relationId).param("at", createdAt)
                .update();
    }

    /** Apaga a aresta SOMENTE se ela for deste vínculo (um {@code removed} antigo não derruba um vínculo novo). */
    public void deleteIfRelation(UUID relationId, UUID follower, UUID followee) {
        jdbc.sql("delete from follow_edge where follower_id = :follower and followee_id = :followee and relation_id = :relation")
                .param("follower", follower).param("followee", followee).param("relation", relationId).update();
    }

    public void markRemoved(UUID relationId, OffsetDateTime at) {
        jdbc.sql("insert into follow_removed (relation_id, removed_at) values (:id, :at) on conflict (relation_id) do nothing")
                .param("id", relationId).param("at", at).update();
    }

    public int pruneRemovedBefore(OffsetDateTime limit) {
        return jdbc.sql("delete from follow_removed where removed_at < :limit").param("limit", limit).update();
    }

    /** Conta excluída: some dos dois lados do grafo local. */
    public int deleteAllOf(UUID userId) {
        return jdbc.sql("delete from follow_edge where follower_id = :id or followee_id = :id").param("id", userId).update();
    }
}
