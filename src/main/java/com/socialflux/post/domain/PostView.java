package com.socialflux.post.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Uma postagem como {@code viewer} a vê: dados da postagem, autor (cópia local), contadores, se ele curtiu e o resumo do
 * pai. {@code imageKey} e afins são a capa (1ª imagem); {@code images} traz todas, em ordem. {@code parentUsername} só vem
 * preenchido quando o pai também é visível para quem pede.
 */
public record PostView(
        UUID id,
        AuthorSnapshot author,
        UUID parentId,
        String parentUsername,
        String body,
        String imageKey,
        String imageKind,
        Integer imageWidth,
        Integer imageHeight,
        List<PostImage> images,
        int likes,
        int replies,
        boolean liked,
        OffsetDateTime createdAt) {

    public Cursor cursor() {
        return new Cursor(createdAt, id);
    }
}
