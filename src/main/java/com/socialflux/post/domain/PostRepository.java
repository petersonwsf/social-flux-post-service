package com.socialflux.post.domain;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acesso às postagens. A regra de visibilidade (D6) é aplicada em SQL, sempre com o mesmo fragmento:
 * <pre>autor ACTIVE e (autor = viewer OU autor público OU existe aresta viewer → autor)</pre>
 * Postagem inexistente, de conta excluída ou invisível simplesmente não volta (o serviço responde 404 igual).
 */
@Repository
public class PostRepository {

    /** Filtros da lista de um autor. */
    public enum AuthorFilter { POSTS, INTERACTIONS, MEDIA }

    /** Linha travada para escrita (exclusão, curtida, resposta). */
    public record Locked(UUID id, UUID authorId, UUID parentId, String imageKey, boolean visible) {
    }

    /** Postagem de uma subárvore (a própria e todas as descendentes), para exclusão. */
    public record Node(UUID id, UUID authorId, String imageKey, int depth) {
    }

    /** Imagem localizada pela chave e se quem pede pode vê-la. */
    public record MediaRef(String imageKey, String imageKind, boolean visible) {
    }

    /** Ancestral com a sua distância (1 = pai) e se é visível. */
    public record Ancestor(PostView post, int depth, boolean visible) {
    }

    /** Contadores gravados × recalculados de uma postagem. */
    public record CountCheck(UUID postId, int likesBefore, int likesAfter, int repliesBefore, int repliesAfter) {

        public boolean diverges() {
            return likesBefore != likesAfter || repliesBefore != repliesAfter;
        }
    }

    /** Fragmento de visibilidade para o autor de alias {@code a}, com a aresta de alias {@code edge}. */
    static String visible(String a, String edge) {
        return "(" + a + ".status = 'ACTIVE' and (" + a + ".user_id = :viewer or not " + a + ".is_private "
                + "or exists (select 1 from follow_edge " + edge + " where " + edge + ".follower_id = :viewer and "
                + edge + ".followee_id = " + a + ".user_id)))";
    }

    private static final String VIEW_COLUMNS = """
            p.id, p.author_id, p.parent_id, p.body, p.image_key, p.image_kind, p.image_width, p.image_height,
            p.likes_count, p.replies_count, p.created_at,
            a.user_id, a.username, a.display_name, a.avatar_key, a.is_private, a.status, a.profile_version,
            case when pa.user_id is not null and %s then pa.username end as parent_username,
            exists (select 1 from post_like l where l.post_id = p.id and l.user_id = :viewer) as liked
            """.formatted(visible("pa", "fe_parent"));

    private static final String VIEW_FROM = """
            from post p
            join author_snapshot a on a.user_id = p.author_id
            left join post pp on pp.id = p.parent_id
            left join author_snapshot pa on pa.user_id = pp.author_id
            """;

    private static final String ORDER_PAGE = " order by p.created_at desc, p.id desc limit :limit";
    private static final String AFTER_CURSOR = " and (p.created_at, p.id) < (:cursorAt, :cursorId)";

    static final RowMapper<PostView> VIEW = (rs, n) -> new PostView(
            rs.getObject("id", UUID.class),
            AuthorRepository.MAPPER.mapRow(rs, n),
            rs.getObject("parent_id", UUID.class),
            rs.getString("parent_username"),
            rs.getString("body"),
            rs.getString("image_key"),
            rs.getString("image_kind"),
            rs.getObject("image_width", Integer.class),
            rs.getObject("image_height", Integer.class),
            List.of(),
            rs.getInt("likes_count"),
            rs.getInt("replies_count"),
            rs.getBoolean("liked"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public PostRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ leitura

    public Optional<PostView> findVisible(UUID viewer, UUID id) {
        return jdbc.sql("select " + VIEW_COLUMNS + VIEW_FROM + " where p.id = :id and " + visible("a", "fe"))
                .param("viewer", viewer).param("id", id).query(VIEW).optional()
                .map(view -> withImages(List.of(view)).get(0));
    }

    /** Feed: postagens (não respostas) do próprio {@code viewer} e de quem ele segue, mais novas primeiro. */
    public List<PostView> feed(UUID viewer, Cursor cursor, int limit) {
        String sql = "select " + VIEW_COLUMNS + VIEW_FROM
                + " where p.parent_id is null and a.status = 'ACTIVE'"
                + " and (p.author_id = :viewer or exists (select 1 from follow_edge e"
                + " where e.followee_id = p.author_id and e.follower_id = :viewer))"
                + (cursor == null ? "" : AFTER_CURSOR) + ORDER_PAGE;
        return page(jdbc.sql(sql).param("viewer", viewer), cursor, limit);
    }

    /** Postagens de um autor (a permissão de ver o perfil já foi conferida por quem chama). */
    public List<PostView> byAuthor(UUID viewer, UUID authorId, AuthorFilter filter, Cursor cursor, int limit) {
        String condition = switch (filter) {
            case POSTS -> " and p.parent_id is null";
            case INTERACTIONS -> "";
            case MEDIA -> " and p.image_key is not null";
        };
        String sql = "select " + VIEW_COLUMNS + VIEW_FROM + " where p.author_id = :author and a.status = 'ACTIVE'"
                + condition + (cursor == null ? "" : AFTER_CURSOR) + ORDER_PAGE;
        return page(jdbc.sql(sql).param("viewer", viewer).param("author", authorId), cursor, limit);
    }

    /** Respostas diretas, cada uma filtrada pela visibilidade do SEU autor. */
    public List<PostView> replies(UUID viewer, UUID parentId, Cursor cursor, int limit) {
        String sql = "select " + VIEW_COLUMNS + VIEW_FROM + " where p.parent_id = :parent and " + visible("a", "fe")
                + (cursor == null ? "" : AFTER_CURSOR) + ORDER_PAGE;
        return page(jdbc.sql(sql).param("viewer", viewer).param("parent", parentId), cursor, limit);
    }

    /** Cadeia de pais (até {@code maxDepth} níveis), do mais próximo ao mais distante, com a visibilidade de cada um. */
    public List<Ancestor> ancestors(UUID viewer, UUID id, int maxDepth) {
        String sql = """
                with recursive chain (id, parent_id, depth) as (
                    select id, parent_id, 0 from post where id = :id
                    union all
                    select q.id, q.parent_id, c.depth + 1 from post q join chain c on q.id = c.parent_id where c.depth < :max
                )
                select %s, c.depth as chain_depth, %s as is_visible
                """.formatted(VIEW_COLUMNS, visible("a", "fe"))
                + VIEW_FROM.replace("from post p", "from chain c join post p on p.id = c.id")
                + " where c.depth > 0 order by c.depth";
        List<Ancestor> chain = jdbc.sql(sql).param("viewer", viewer).param("id", id).param("max", maxDepth)
                .query((rs, n) -> new Ancestor(VIEW.mapRow(rs, n), rs.getInt("chain_depth"), rs.getBoolean("is_visible")))
                .list();
        List<PostView> withImages = withImages(chain.stream().map(Ancestor::post).toList());
        return java.util.stream.IntStream.range(0, chain.size())
                .mapToObj(i -> new Ancestor(withImages.get(i), chain.get(i).depth(), chain.get(i).visible())).toList();
    }

    public Optional<MediaRef> findMedia(UUID viewer, String imageKey) {
        return jdbc.sql("select i.image_key, i.image_kind, " + visible("a", "fe") + " as is_visible from post_image i "
                        + "join post p on p.id = i.post_id join author_snapshot a on a.user_id = p.author_id where i.image_key = :key")
                .param("viewer", viewer).param("key", imageKey)
                .query((rs, n) -> new MediaRef(rs.getString("image_key"), rs.getString("image_kind"),
                        rs.getBoolean("is_visible")))
                .optional();
    }

    private List<PostView> page(JdbcClient.StatementSpec spec, Cursor cursor, int limit) {
        if (cursor != null) {
            spec = spec.param("cursorAt", cursor.at()).param("cursorId", cursor.id());
        }
        return withImages(spec.param("limit", limit).query(VIEW).list());
    }

    // ------------------------------------------------------------------ escrita

    /**
     * Trava SÓ a linha da postagem ({@code for update of p}; a cópia do autor não é travada) e informa se ela é visível
     * para {@code viewer}. Vazio se não existir.
     */
    public Optional<Locked> lockForUpdate(UUID viewer, UUID id) {
        return jdbc.sql("select p.id, p.author_id, p.parent_id, p.image_key, " + visible("a", "fe") + " as is_visible "
                        + "from post p join author_snapshot a on a.user_id = p.author_id where p.id = :id for update of p")
                .param("viewer", viewer).param("id", id)
                .query((rs, n) -> new Locked(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class),
                        rs.getObject("parent_id", UUID.class), rs.getString("image_key"), rs.getBoolean("is_visible")))
                .optional();
    }

    /** Lê o pai sem travar (para travar o pai ANTES do filho na exclusão: mesma ordem da exclusão em cascata). */
    public Optional<UUID> parentOf(UUID id) {
        return jdbc.sql("select parent_id from post where id = :id").param("id", id)
                .query((rs, n) -> Optional.ofNullable(rs.getObject("parent_id", UUID.class))).optional()
                .flatMap(parent -> parent);
    }

    public void insert(UUID id, UUID authorId, UUID parentId, String body, String imageKey, String imageKind,
                       Integer width, Integer height, OffsetDateTime createdAt) {
        jdbc.sql("""
                        insert into post (id, author_id, parent_id, body, image_key, image_kind, image_width, image_height, created_at)
                        values (:id, :author, :parent, :body, :key, :kind, :width, :height, :at)""")
                .param("id", id).param("author", authorId).param("parent", parentId).param("body", body)
                .param("key", imageKey).param("kind", imageKind).param("width", width).param("height", height)
                .param("at", createdAt)
                .update();
    }

    public void addReplies(UUID id, int delta) {
        jdbc.sql("update post set replies_count = greatest(replies_count + :delta, 0) where id = :id")
                .param("delta", delta).param("id", id).update();
    }

    /**
     * A postagem e todas as descendentes, travadas para escrita na ordem pai → filho (por profundidade): a mesma ordem
     * que a exclusão de uma resposta usa (trava o pai, depois o filho), o que evita impasse entre exclusões simultâneas.
     */
    public List<Node> lockSubtree(UUID id) {
        return jdbc.sql("""
                        with recursive tree (id, depth) as (
                            select id, 0 from post where id = :id
                            union all
                            select c.id, t.depth + 1 from post c join tree t on c.parent_id = t.id
                        )
                        select p.id, p.author_id, p.image_key, t.depth from post p join tree t on t.id = p.id
                        order by t.depth, p.id
                        for update of p""")
                .param("id", id)
                .query((rs, n) -> new Node(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class),
                        rs.getString("image_key"), rs.getInt("depth")))
                .list();
    }

    /** Grava as imagens da postagem em ordem (a 1ª é a capa, também copiada para {@code post.image_*}). */
    public void insertImages(UUID postId, List<PostImage> images) {
        for (PostImage image : images) {
            jdbc.sql("""
                            insert into post_image (post_id, position, image_key, image_kind, image_width, image_height)
                            values (:post, :position, :key, :kind, :width, :height)""")
                    .param("post", postId).param("position", image.position()).param("key", image.key())
                    .param("kind", image.kind()).param("width", image.width()).param("height", image.height()).update();
        }
    }

    /** Chaves de TODAS as imagens destas postagens (para apagar os objetos depois da exclusão). */
    public List<String> imageKeysOf(List<UUID> postIds) {
        if (postIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select image_key from post_image where post_id = any(:ids)")
                .param("ids", postIds.toArray(new UUID[0])).query(String.class).list();
    }

    /** Preenche {@code images} (todas as imagens, em ordem) das postagens, numa só consulta. */
    private List<PostView> withImages(List<PostView> views) {
        if (views.isEmpty()) {
            return views;
        }
        java.util.Map<UUID, List<PostImage>> byPost = new java.util.HashMap<>();
        jdbc.sql("select post_id, position, image_key, image_kind, image_width, image_height from post_image "
                        + "where post_id = any(:ids) order by post_id, position")
                .param("ids", views.stream().map(PostView::id).distinct().toArray(UUID[]::new))
                .query((rs, n) -> {
                    byPost.computeIfAbsent(rs.getObject("post_id", UUID.class), k -> new java.util.ArrayList<>())
                            .add(new PostImage(rs.getString("image_key"), rs.getString("image_kind"),
                                    rs.getObject("image_width", Integer.class), rs.getObject("image_height", Integer.class),
                                    rs.getInt("position")));
                    return n;
                }).list();
        return views.stream().map(v -> new PostView(v.id(), v.author(), v.parentId(), v.parentUsername(), v.body(),
                v.imageKey(), v.imageKind(), v.imageWidth(), v.imageHeight(), byPost.getOrDefault(v.id(), List.of()),
                v.likes(), v.replies(), v.liked(), v.createdAt())).toList();
    }

    /** Apaga a postagem; a FK {@code on delete cascade} leva respostas e curtidas junto. */
    public void delete(UUID id) {
        jdbc.sql("delete from post where id = :id").param("id", id).update();
    }

    /** Postagens de um autor, mais antigas primeiro (limpeza de conta excluída). */
    public List<UUID> idsByAuthor(UUID authorId, int limit) {
        return jdbc.sql("select id from post where author_id = :author order by created_at, id limit :limit")
                .param("author", authorId).param("limit", limit).query(UUID.class).list();
    }

    // ------------------------------------------------------------------ curtidas

    /** {@code true} se a curtida foi criada agora (repetir não duplica). */
    public boolean insertLike(UUID postId, UUID userId, OffsetDateTime at) {
        return jdbc.sql("insert into post_like (post_id, user_id, created_at) values (:post, :user, :at) "
                        + "on conflict (post_id, user_id) do nothing")
                .param("post", postId).param("user", userId).param("at", at).update() == 1;
    }

    /** {@code true} se havia curtida e ela foi removida agora. */
    public boolean deleteLike(UUID postId, UUID userId) {
        return jdbc.sql("delete from post_like where post_id = :post and user_id = :user")
                .param("post", postId).param("user", userId).update() == 1;
    }

    /** Soma {@code delta} ao contador de curtidas e devolve o valor novo. */
    public int addLikes(UUID id, int delta) {
        return jdbc.sql("update post set likes_count = greatest(likes_count + :delta, 0) where id = :id returning likes_count")
                .param("delta", delta).param("id", id).query(Integer.class).single();
    }

    public int likesOf(UUID id) {
        return jdbc.sql("select likes_count from post where id = :id").param("id", id).query(Integer.class).single();
    }

    /** Postagens que o usuário curtiu (limpeza de conta excluída). */
    public List<UUID> likedBy(UUID userId, int limit) {
        return jdbc.sql("select post_id from post_like where user_id = :user order by created_at limit :limit")
                .param("user", userId).param("limit", limit).query(UUID.class).list();
    }

    // ------------------------------------------------------------------ mídia e manutenção

    /** Quais destas chaves ainda pertencem a alguma postagem. */
    public List<String> existingImageKeys(List<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select image_key from post_image where image_key = any(:keys)")
                .param("keys", keys.toArray(new String[0])).query(String.class).list();
    }

    /** Postagens cujos contadores não batem com as linhas reais (só leitura; no máximo {@code limit}). */
    public List<UUID> findCountDivergences(int limit) {
        return jdbc.sql("""
                        select p.id from post p
                        where p.likes_count <> (select count(*) from post_like l where l.post_id = p.id)
                           or p.replies_count <> (select count(*) from post r where r.parent_id = p.id)
                        limit :limit""")
                .param("limit", limit).query(UUID.class).list();
    }

    /** Trava a postagem, recalcula os contadores por {@code count(*)} e corrige se divergirem. */
    public Optional<CountCheck> reconcileCounts(UUID id) {
        Optional<int[]> before = jdbc.sql("select likes_count, replies_count from post where id = :id for update")
                .param("id", id).query((rs, n) -> new int[]{rs.getInt(1), rs.getInt(2)}).optional();
        if (before.isEmpty()) {
            return Optional.empty();
        }
        int likes = jdbc.sql("select count(*) from post_like where post_id = :id").param("id", id)
                .query(Integer.class).single();
        int replies = jdbc.sql("select count(*) from post where parent_id = :id").param("id", id)
                .query(Integer.class).single();
        CountCheck check = new CountCheck(id, before.get()[0], likes, before.get()[1], replies);
        if (check.diverges()) {
            jdbc.sql("update post set likes_count = :likes, replies_count = :replies where id = :id")
                    .param("likes", likes).param("replies", replies).param("id", id).update();
        }
        return Optional.of(check);
    }
}
