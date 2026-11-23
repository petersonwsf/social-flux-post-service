package com.socialflux.post.web;

import com.socialflux.post.service.PostMediaService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Entrega as imagens de postagem (D8): só para quem pode ver a postagem — chave desconhecida e postagem invisível
 * respondem 404 igual. Cache PRIVADO (a imagem pode ser de conta privada) e imutável (a chave nunca é reutilizada);
 * a revalidação por {@code ETag} refaz a checagem de visibilidade e responde 304 sem ler o objeto. Tipo fixo + {@code nosniff}.
 */
@RestController
public class MediaController {

    private final PostMediaService media;

    public MediaController(PostMediaService media) {
        this.media = media;
    }

    @GetMapping("/media/posts/{key}")
    ResponseEntity<byte[]> image(@PathVariable String key,
                                 @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                 @AuthenticationPrincipal Jwt jwt) {
        return media.deliver(PostController.actor(jwt), key, ifNoneMatch)
                .map(delivery -> {
                    ResponseEntity.BodyBuilder builder = ResponseEntity.status(delivery.notModified()
                                    ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                            .eTag("\"" + delivery.key() + "\"")
                            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                            .header("X-Content-Type-Options", "nosniff");
                    if (delivery.notModified()) {
                        return builder.<byte[]>build();
                    }
                    return builder.contentType(MediaType.parseMediaType(delivery.contentType())).body(delivery.bytes());
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
