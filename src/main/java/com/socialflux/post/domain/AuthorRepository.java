package com.socialflux.post.domain;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Acesso à cópia local dos perfis dos autores ({@code author_snapshot}). */
@Repository
public class AuthorRepository {

    static final String COLUMNS = "user_id, username, display_name, avatar_key, is_private, status, profile_version";
    static final RowMapper<AuthorSnapshot> MAPPER = (rs, n) -> new AuthorSnapshot(
            rs.getObject("user_id", UUID.class),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("avatar_key"),
            rs.getBoolean("is_private"),
            rs.getString("status"),
            rs.getLong("profile_version"));

    private final JdbcClient jdbc;

    public AuthorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AuthorSnapshot> find(UUID userId) {
        return jdbc.sql("select " + COLUMNS + " from author_snapshot where user_id = :id")
                .param("id", userId).query(MAPPER).optional();
    }

    /**
     * Lê travando em modo COMPARTILHADO: quem cria uma postagem espera o processamento de um {@code profile.deleted} do
     * mesmo autor (que trava a linha para ESCREVER) terminar, e vice-versa — não nasce postagem de conta já excluída.
     */
    public Optional<AuthorSnapshot> findForShare(UUID userId) {
        return jdbc.sql("select " + COLUMNS + " from author_snapshot where user_id = :id for share")
                .param("id", userId).query(MAPPER).optional();
    }

    /** Lê travando a linha para ESCRITA (aplicação de eventos de perfil). */
    public Optional<AuthorSnapshot> findForUpdate(UUID userId) {
        return jdbc.sql("select " + COLUMNS + " from author_snapshot where user_id = :id for update")
                .param("id", userId).query(MAPPER).optional();
    }

    /** O viewer segue o autor? (aresta aceita na cópia local do grafo) */
    public boolean follows(UUID follower, UUID followee) {
        return jdbc.sql("select exists (select 1 from follow_edge where follower_id = :f and followee_id = :t)")
                .param("f", follower).param("t", followee).query(Boolean.class).single();
    }

    public void insert(AuthorSnapshot s, OffsetDateTime now) {
        jdbc.sql("insert into author_snapshot (user_id, username, display_name, avatar_key, is_private, status, "
                        + "profile_version, updated_at) values (:id, :username, :display, :avatar, :private, "
                        + ":status, :version, :now)")
                .param("id", s.userId()).param("username", s.username())
                .param("display", s.displayName()).param("avatar", s.avatarKey()).param("private", s.isPrivate())
                .param("status", s.status()).param("version", s.profileVersion()).param("now", now)
                .update();
    }

    public void update(AuthorSnapshot s, OffsetDateTime now) {
        jdbc.sql("update author_snapshot set username = :username, display_name = :display, "
                        + "avatar_key = :avatar, is_private = :private, status = :status, profile_version = :version, "
                        + "updated_at = :now where user_id = :id")
                .param("id", s.userId()).param("username", s.username())
                .param("display", s.displayName()).param("avatar", s.avatarKey()).param("private", s.isPrivate())
                .param("status", s.status()).param("version", s.profileVersion()).param("now", now)
                .update();
    }

    /** Contas excluídas que ainda têm postagens, curtidas ou arestas a apagar (D12). */
    public List<UUID> deletedWithLeftovers(int limit) {
        return jdbc.sql("""
                        select a.user_id from author_snapshot a
                        where a.status = 'DELETED' and (
                              exists (select 1 from post p where p.author_id = a.user_id)
                           or exists (select 1 from post_like l where l.user_id = a.user_id)
                           or exists (select 1 from follow_edge e where e.follower_id = a.user_id or e.followee_id = a.user_id))
                        limit :limit""")
                .param("limit", limit).query(UUID.class).list();
    }
}
