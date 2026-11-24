package com.socialflux.post.web;

import com.socialflux.post.service.LikeService;
import com.socialflux.post.service.PostService;
import com.socialflux.post.web.Dtos.AncestorsView;
import com.socialflux.post.web.Dtos.LikeView;
import com.socialflux.post.web.Dtos.PageView;
import com.socialflux.post.web.Dtos.Post;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * API pública das postagens (D4). Regras de segurança desta classe:
 * <ul>
 *   <li>quem age vem SEMPRE do {@code sub} do token; nenhuma rota aceita autor, {@code id_usuario} ou id de curtida;</li>
 *   <li>{@code {id}} é só o alvo: postagem inexistente, de conta excluída ou invisível respondem 404 igual;</li>
 *   <li>listas paginadas por cursor ({@code limite} 1–100, padrão 20).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/postagens")
public class PostController {

    private final PostService posts;
    private final LikeService likes;

    public PostController(PostService posts, LikeService likes) {
        this.posts = posts;
        this.likes = likes;
    }

    /**
     * Cria postagem ou resposta (multipart: {@code legenda}, {@code imagens} (até 5, repetido), {@code pai}). O campo
     * antigo {@code imagem} (uma só) continua aceito e vale como a primeira.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Post> create(@RequestParam(name = "legenda", required = false) String caption,
                                @RequestPart(name = "imagem", required = false) MultipartFile image,
                                @RequestPart(name = "imagens", required = false) List<MultipartFile> images,
                                @RequestParam(name = "pai", required = false) String parent,
                                @AuthenticationPrincipal Jwt jwt) throws IOException {
        List<byte[]> uploads = new ArrayList<>();
        List<MultipartFile> files = new ArrayList<>();
        if (image != null) {
            files.add(image);
        }
        if (images != null) {
            files.addAll(images);
        }
        for (MultipartFile file : files) {
            if (!file.isEmpty()) {
                uploads.add(file.getBytes());
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Post.of(posts.create(actor(jwt), caption, uploads, parent)));
    }

    @GetMapping("/feed")
    PageView feed(@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limite,
                  @AuthenticationPrincipal Jwt jwt) {
        return PageView.of(posts.feed(actor(jwt), cursor, limite));
    }

    @GetMapping
    PageView byAuthor(@RequestParam(required = false) String autor, @RequestParam(required = false) String filtro,
                      @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limite,
                      @AuthenticationPrincipal Jwt jwt) {
        return PageView.of(posts.byAuthor(actor(jwt), autor, filtro, cursor, limite));
    }

    @GetMapping("/{id}")
    Post get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return Post.of(posts.get(actor(jwt), postId(id)));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        posts.delete(actor(jwt), postId(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/respostas")
    PageView replies(@PathVariable String id, @RequestParam(required = false) String cursor,
                     @RequestParam(required = false) Integer limite, @AuthenticationPrincipal Jwt jwt) {
        return PageView.of(posts.replies(actor(jwt), postId(id), cursor, limite));
    }

    @GetMapping("/{id}/ancestrais")
    AncestorsView ancestors(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return new AncestorsView(posts.ancestors(actor(jwt), postId(id)).stream().map(Post::of).toList());
    }

    @PutMapping("/{id}/curtida")
    LikeView like(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return LikeView.of(likes.like(actor(jwt), postId(id)));
    }

    @DeleteMapping("/{id}/curtida")
    LikeView unlike(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return LikeView.of(likes.unlike(actor(jwt), postId(id)));
    }

    static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /** Id malformado é tratado como "não existe" (404 uniforme). */
    private static UUID postId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.postNotFound();
        }
    }
}
