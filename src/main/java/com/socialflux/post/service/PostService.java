package com.socialflux.post.service;

import com.socialflux.events.EventIds;
import com.socialflux.post.config.PostProperties;
import com.socialflux.post.domain.AuthorRepository;
import com.socialflux.post.domain.AuthorSnapshot;
import com.socialflux.post.domain.Cursor;
import com.socialflux.post.domain.PostRepository;
import com.socialflux.post.domain.PostView;
import com.socialflux.post.domain.UserRef;
import com.socialflux.post.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Regras das postagens. Quem age vem SEMPRE do token ({@code viewer}/{@code author}); ids na URL são só o alvo.
 * <ul>
 *   <li>Conteúdo: texto (≤ {@code max-body}, sem ser só espaços) e/ou um anexo; pelo menos um dos dois (corrige LOG-08).</li>
 *   <li>Resposta: o pai precisa existir E ser visível para quem responde; senão 404 igual a "não existe".</li>
 *   <li>Visibilidade (D6): dono, autor público ou aresta "eu sigo o autor"; vale em todas as leituras.</li>
 *   <li>Excluir (D7): só o autor; apaga a subárvore e as curtidas; a imagem sai depois do commit.</li>
 * </ul>
 */
@Service
public class PostService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_ANCESTORS = 10;

    /** Página de postagens e o cursor da próxima (ou {@code null}). */
    public record Page(List<PostView> items, String nextCursor) {
    }

    private final PostRepository posts;
    private final AuthorRepository authors;
    private final PostWriter writer;
    private final PostMediaService media;
    private final RateLimiter rateLimiter;
    private final PostProperties.Limits limits;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PostService(PostRepository posts, AuthorRepository authors, PostWriter writer, PostMediaService media,
                       RateLimiter rateLimiter, PostProperties properties, PlatformTransactionManager txManager,
                       Clock clock) {
        this.posts = posts;
        this.authors = authors;
        this.writer = writer;
        this.media = media;
        this.rateLimiter = rateLimiter;
        this.limits = properties.limits();
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ criar

    /**
     * Cria postagem ou resposta (C): valida → processa o anexo FORA da transação → grava o objeto → transação curta
     * (pai travado e visível, linha nova, contador do pai, evento) → se a transação falhar, apaga o objeto (compensação).
     */
    public PostView create(UUID author, String caption, byte[] image, String parentRaw) {
        rateLimiter.hit("post:" + author, limits.postsPerHour(), Duration.ofHours(1));
        String body = normalizeBody(caption);
        boolean hasImage = image != null && image.length > 0;
        UUID parentId = parseOptionalUuid(parentRaw);
        if (body == null && !hasImage) {
            throw ApiException.unprocessable("postagem_vazia", "Escreva um texto ou anexe uma imagem");
        }
        requireActiveAuthor(authors.find(author));
        if (parentId != null && posts.findVisible(author, parentId).isEmpty()) {
            throw ApiException.postNotFound();   // checagem barata antes de processar a imagem; a definitiva é na transação
        }

        PostMediaService.Stored stored = hasImage ? media.store(image) : null;
        UUID id = EventIds.v7(clock);
        try {
            tx.executeWithoutResult(status -> {
                requireActiveAuthor(authors.findForShare(author));
                UUID parentAuthor = null;
                if (parentId != null) {
                    PostRepository.Locked parent = posts.lockForUpdate(author, parentId)
                            .filter(PostRepository.Locked::visible)
                            .orElseThrow(ApiException::postNotFound);
                    parentAuthor = parent.authorId();
                }
                writer.create(id, author, parentId, parentAuthor, body, stored == null ? null : stored.key(),
                        stored == null ? null : stored.media());
            });
        } catch (RuntimeException e) {
            if (stored != null) {
                media.deleteQuietly(stored.key());
            }
            throw e;
        }
        return posts.findVisible(author, id).orElseThrow(ApiException::postNotFound);
    }

    // ------------------------------------------------------------------ ler

    public PostView get(UUID viewer, UUID id) {
        return posts.findVisible(viewer, id).orElseThrow(ApiException::postNotFound);
    }

    public Page feed(UUID viewer, String cursor, Integer limit) {
        int size = limit(limit);
        return page(posts.feed(viewer, Cursor.decode(cursor), size + 1), size);
    }

    /** Lista de um autor. Conta privada: só o dono e quem a segue (403 {@code conta_privada}). */
    public Page byAuthor(UUID viewer, String authorRaw, String filterRaw, String cursor, Integer limit) {
        PostRepository.AuthorFilter filter = parseFilter(filterRaw);
        int size = limit(limit);
        Cursor after = Cursor.decode(cursor);
        AuthorSnapshot author = resolveAuthor(authorRaw);
        if (author.isPrivate() && !author.userId().equals(viewer) && !authors.follows(viewer, author.userId())) {
            throw ApiException.forbidden("conta_privada", "Esta conta é privada");
        }
        return page(posts.byAuthor(viewer, author.userId(), filter, after, size + 1), size);
    }

    /** Respostas de uma postagem visível; cada resposta é filtrada pela visibilidade do seu autor. */
    public Page replies(UUID viewer, UUID id, String cursor, Integer limit) {
        int size = limit(limit);
        Cursor after = Cursor.decode(cursor);
        get(viewer, id);
        return page(posts.replies(viewer, id, after, size + 1), size);
    }

    /** Cadeia de pais (D13), da mais antiga à mais próxima, parando no primeiro nível não visível. */
    public List<PostView> ancestors(UUID viewer, UUID id) {
        get(viewer, id);
        List<PostView> chain = new ArrayList<>();
        for (PostRepository.Ancestor ancestor : posts.ancestors(viewer, id, MAX_ANCESTORS)) {
            if (!ancestor.visible()) {
                break;
            }
            chain.add(ancestor.post());
        }
        Collections.reverse(chain);
        return chain;
    }

    // ------------------------------------------------------------------ excluir

    /**
     * Exclui a própria postagem. Inexistente ou invisível → 404; visível mas de outra pessoa → 403. Trava o pai ANTES da
     * postagem (mesma ordem da exclusão em cascata do pai), para não haver impasse entre exclusões simultâneas.
     */
    public void delete(UUID viewer, UUID id) {
        tx.executeWithoutResult(status -> {
            Optional<UUID> parent = posts.parentOf(id);
            parent.ifPresent(parentId -> posts.lockForUpdate(viewer, parentId));
            PostRepository.Locked post = posts.lockForUpdate(viewer, id)
                    .filter(PostRepository.Locked::visible)
                    .orElseThrow(ApiException::postNotFound);
            if (!post.authorId().equals(viewer)) {
                throw ApiException.forbidden("forbidden", "Você só pode excluir as suas postagens");
            }
            writer.deleteTree(post.id(), post.parentId());
        });
    }

    // ------------------------------------------------------------------ auxiliares

    private String normalizeBody(String caption) {
        if (caption == null || caption.isBlank()) {
            return null;
        }
        String body = caption.strip();
        if (body.codePointCount(0, body.length()) > limits.maxBody()) {
            throw ApiException.unprocessable("texto_muito_longo",
                    "O texto pode ter no máximo " + limits.maxBody() + " caracteres");
        }
        return body;
    }

    private static void requireActiveAuthor(Optional<AuthorSnapshot> author) {
        if (author.isEmpty() || !author.get().isActive()) {
            throw ApiException.unavailable("perfil_indisponivel",
                    "Seu perfil ainda não está disponível para publicar. Tente novamente em instantes.");
        }
    }

    private AuthorSnapshot resolveAuthor(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("parametro_invalido", "Informe o autor");
        }
        UserRef ref = UserRef.parse(raw);
        Optional<AuthorSnapshot> author = authors.find(ref.uuid());
        return author.filter(AuthorSnapshot::isActive)
                .orElseThrow(() -> ApiException.notFound("usuario_nao_encontrado", "Usuário não encontrado"));
    }

    private static PostRepository.AuthorFilter parseFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return PostRepository.AuthorFilter.POSTS;
        }
        return switch (raw) {
            case "posts" -> PostRepository.AuthorFilter.POSTS;
            case "interacoes" -> PostRepository.AuthorFilter.INTERACTIONS;
            case "midia" -> PostRepository.AuthorFilter.MEDIA;
            default -> throw ApiException.badRequest("parametro_invalido", "Filtro inválido (use posts, interacoes ou midia)");
        };
    }

    static int limit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("parametro_invalido", "O limite deve estar entre 1 e " + MAX_LIMIT);
        }
        return limit;
    }

    private static UUID parseOptionalUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("parametro_invalido", "Identificador da postagem inválido");
        }
    }

    private static Page page(List<PostView> rows, int size) {
        if (rows.size() <= size) {
            return new Page(rows, null);
        }
        List<PostView> items = rows.subList(0, size);
        return new Page(List.copyOf(items), items.getLast().cursor().encode());
    }
}
