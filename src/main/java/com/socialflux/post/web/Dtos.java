package com.socialflux.post.web;

import com.socialflux.post.domain.AuthorSnapshot;
import com.socialflux.post.domain.PostView;
import com.socialflux.post.service.LikeService;
import com.socialflux.post.service.PostService;

import java.util.List;

/** Corpos das respostas. Os nomes dos campos seguem o contrato do frontend (português, snake_case onde já era assim). */
public final class Dtos {

    private Dtos() {
    }

    /** Autor: mesmos campos do perfil público e das listas do grafo, sem nenhum dado pessoal. */
    public record Author(
            String id,
            String username,
            String nome,
            String foto_perfil,
            String foto_url,
            boolean privado) {

        public static Author of(AuthorSnapshot s) {
            return new Author(s.userId().toString(), s.username(), s.displayName(), s.avatarKey(),
                    s.avatarKey() == null ? null : "/media/avatars/" + s.avatarKey(), s.isPrivate());
        }
    }

    /** Anexo: URL autenticada, tipo e dimensões (o frontend reserva o espaço antes de carregar). */
    public record Image(String url, String tipo, Integer largura, Integer altura) {
    }

    /** Resumo do pai de uma resposta; {@code autor_username} só quando quem pede também pode ver o pai. */
    public record Parent(String id, String autor_username) {
    }

    public record Post(
            String id,
            String legenda,
            Image imagem,
            String criado_em,
            Author autor,
            Parent pai,
            int curtidas,
            boolean curtido,
            int respostas) {

        public static Post of(PostView p) {
            Image image = p.imageKey() == null ? null
                    : new Image("/media/posts/" + p.imageKey(), p.imageKind(), p.imageWidth(), p.imageHeight());
            Parent parent = p.parentId() == null ? null : new Parent(p.parentId().toString(), p.parentUsername());
            return new Post(p.id().toString(), p.body(), image, p.createdAt().toInstant().toString(), Author.of(p.author()),
                    parent, p.likes(), p.liked(), p.replies());
        }
    }

    public record PageView(List<Post> itens, String proximoCursor) {

        public static PageView of(PostService.Page page) {
            return new PageView(page.items().stream().map(Post::of).toList(), page.nextCursor());
        }
    }

    public record AncestorsView(List<Post> itens) {
    }

    public record LikeView(boolean curtido, int curtidas) {

        public static LikeView of(LikeService.LikeState state) {
            return new LikeView(state.liked(), state.likes());
        }
    }
}
