package com.socialflux.post.moderation;

/** Imagem a analisar: os bytes de um formato que o provedor aceita (JPEG ou PNG) e o tipo de conteúdo. */
public record ModerationImage(byte[] bytes, String contentType) {
}
