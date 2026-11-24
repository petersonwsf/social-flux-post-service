package com.socialflux.post.service;

import com.socialflux.events.Outbox;
import com.socialflux.media.ProcessedMedia;
import com.socialflux.post.domain.PostImage;
import com.socialflux.post.domain.PostRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * As transições de estado das postagens. Cada método faz, NA MESMA TRANSAÇÃO (que o chamador precisa ter aberto), a mudança
 * nas tabelas, o ajuste dos contadores e a gravação do evento no outbox. As REGRAS (quem pode, visibilidade, limites) ficam
 * no {@link PostService} e no {@link LikeService}; aqui só há o mecanismo. O chamador já travou as linhas envolvidas.
 * <p>Eventos (D11): o agregado é a postagem ({@code aggregateVersion} = 1); só ids, datas e um trecho do texto — nunca
 * dados pessoais.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class PostWriter {

    public static final String POST_CREATED = "post.created.v1";
    public static final String POST_DELETED = "post.deleted.v1";
    public static final String POST_LIKED = "post.liked.v1";
    public static final String POST_UNLIKED = "post.unliked.v1";

    static final int EXCERPT_LENGTH = 120;

    private final PostRepository posts;
    private final PostMediaService media;
    private final Outbox outbox;
    private final Clock clock;

    public PostWriter(PostRepository posts, PostMediaService media, Outbox outbox, Clock clock) {
        this.posts = posts;
        this.media = media;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Grava a postagem (ou resposta, com o pai já travado), soma 1 às respostas do pai e grava {@code post.created.v1}. */
    public void create(UUID id, UUID authorId, UUID parentId, UUID parentAuthorId, String body,
                       List<PostMediaService.Stored> images) {
        OffsetDateTime at = now();
        PostMediaService.Stored cover = images.isEmpty() ? null : images.get(0);
        String kind = cover == null ? null : cover.media().kind().name();
        posts.insert(id, authorId, parentId, body, cover == null ? null : cover.key(), kind,
                cover == null ? null : cover.media().width(), cover == null ? null : cover.media().height(), at);
        List<PostImage> rows = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            ProcessedMedia m = images.get(i).media();
            rows.add(new PostImage(images.get(i).key(), m.kind().name(), m.width(), m.height(), i));
        }
        posts.insertImages(id, rows);
        if (parentId != null) {
            posts.addReplies(parentId, 1);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("postId", id.toString());
        data.put("authorId", authorId.toString());
        data.put("parentId", parentId == null ? null : parentId.toString());
        data.put("parentAuthorId", parentAuthorId == null ? null : parentAuthorId.toString());
        data.put("mediaKind", kind);
        data.put("excerpt", excerpt(body));
        data.put("createdAt", at.toInstant().toString());
        outbox.add(POST_CREATED, id, 1, data);
    }

    /**
     * Exclui a postagem e toda a subárvore (D7): trava a subárvore (pai → filho), apaga (a FK leva respostas e curtidas),
     * desconta 1 das respostas do pai direto, grava um {@code post.deleted.v1} por postagem removida e agenda a remoção das
     * imagens para DEPOIS do commit (a falta de um arquivo nunca impede a exclusão: LOG-05).
     */
    public int deleteTree(UUID id, UUID parentId) {
        List<PostRepository.Node> nodes = posts.lockSubtree(id);
        if (nodes.isEmpty()) {
            return 0;
        }
        List<String> keys = posts.imageKeysOf(nodes.stream().map(PostRepository.Node::id).toList());
        posts.delete(id);
        if (parentId != null) {
            posts.addReplies(parentId, -1);
        }
        for (PostRepository.Node node : nodes) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("postId", node.id().toString());
            data.put("authorId", node.authorId().toString());
            outbox.add(POST_DELETED, node.id(), 1, data);
        }
        media.deleteAfterCommit(keys);
        return nodes.size();
    }

    /** Curte. Devolve {@code true} se a curtida é nova (repetir não muda nada nem gera evento). */
    public boolean like(UUID postId, UUID postAuthorId, UUID userId) {
        if (!posts.insertLike(postId, userId, now())) {
            return false;
        }
        posts.addLikes(postId, 1);
        outbox.add(POST_LIKED, postId, 1, likeData(postId, postAuthorId, userId));
        return true;
    }

    /** Descurte. Devolve {@code true} se havia curtida. */
    public boolean unlike(UUID postId, UUID postAuthorId, UUID userId) {
        if (!posts.deleteLike(postId, userId)) {
            return false;
        }
        posts.addLikes(postId, -1);
        outbox.add(POST_UNLIKED, postId, 1, likeData(postId, postAuthorId, userId));
        return true;
    }

    private OffsetDateTime now() {
        // O PostgreSQL guarda microssegundos: truncar aqui mantém o cursor de paginação idêntico ao valor gravado.
        Instant instant = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static String excerpt(String body) {
        if (body == null) {
            return null;
        }
        if (body.codePointCount(0, body.length()) <= EXCERPT_LENGTH) {
            return body;
        }
        return body.substring(0, body.offsetByCodePoints(0, EXCERPT_LENGTH));
    }

    private static Map<String, Object> likeData(UUID postId, UUID postAuthorId, UUID userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("postId", postId.toString());
        data.put("postAuthorId", postAuthorId.toString());
        data.put("userId", userId.toString());
        return data;
    }
}
