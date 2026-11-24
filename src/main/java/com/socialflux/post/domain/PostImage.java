package com.socialflux.post.domain;

/** Uma imagem de uma postagem, na ordem em que o autor a anexou ({@code position} começa em 0). */
public record PostImage(String key, String kind, Integer width, Integer height, int position) {
}
