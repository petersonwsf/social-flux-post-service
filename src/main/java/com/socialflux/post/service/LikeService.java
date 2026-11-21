package com.socialflux.post.service;

import com.socialflux.post.config.PostProperties;
import com.socialflux.post.domain.PostRepository;
import com.socialflux.post.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

/**
 * Curtir e descurtir (H). Idempotente: {@code insert … on conflict do nothing} / {@code delete}, e o contador só muda quando
 * a curtida de fato mudou. Só em postagem VISÍVEL (senão 404). A linha da postagem é travada antes, o que serializa curtidas,
 * descurtidas e a exclusão da mesma postagem — o contador nunca desbalanceia. Pode curtir a própria postagem.
 * Quem curte vem do token: não há id de usuário nem de curtida vindo do cliente (corrige SEC-02).
 */
@Service
public class LikeService {

    /** Estado depois da operação. */
    public record LikeState(boolean liked, int likes) {
    }

    private final PostRepository posts;
    private final PostWriter writer;
    private final RateLimiter rateLimiter;
    private final PostProperties.Limits limits;
    private final TransactionTemplate tx;

    public LikeService(PostRepository posts, PostWriter writer, RateLimiter rateLimiter, PostProperties properties,
                       PlatformTransactionManager txManager) {
        this.posts = posts;
        this.writer = writer;
        this.rateLimiter = rateLimiter;
        this.limits = properties.limits();
        this.tx = new TransactionTemplate(txManager);
    }

    public LikeState like(UUID viewer, UUID postId) {
        rateLimiter.hit("like:" + viewer, limits.likesPerHour(), Duration.ofHours(1));
        return tx.execute(status -> {
            PostRepository.Locked post = lockVisible(viewer, postId);
            writer.like(post.id(), post.authorId(), viewer);
            return new LikeState(true, posts.likesOf(post.id()));
        });
    }

    public LikeState unlike(UUID viewer, UUID postId) {
        rateLimiter.hit("like:" + viewer, limits.likesPerHour(), Duration.ofHours(1));
        return tx.execute(status -> {
            PostRepository.Locked post = lockVisible(viewer, postId);
            writer.unlike(post.id(), post.authorId(), viewer);
            return new LikeState(false, posts.likesOf(post.id()));
        });
    }

    private PostRepository.Locked lockVisible(UUID viewer, UUID postId) {
        return posts.lockForUpdate(viewer, postId)
                .filter(PostRepository.Locked::visible)
                .orElseThrow(ApiException::postNotFound);
    }
}
