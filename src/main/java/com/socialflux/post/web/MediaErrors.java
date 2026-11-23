package com.socialflux.post.web;

import com.socialflux.media.MediaException;

/** Traduz as recusas da biblioteca de mídia para os códigos públicos do serviço (§8 do plano). */
public final class MediaErrors {

    private MediaErrors() {
    }

    public static ApiException translate(MediaException e) {
        return switch (e.reason()) {
            case EMPTY -> ApiException.unprocessable("postagem_vazia", "Escreva um texto ou anexe uma imagem");
            case TOO_LARGE -> ApiException.tooLarge("imagem_muito_grande",
                    "O arquivo é grande demais (imagens até 5 MB, GIFs até 8 MB)");
            case DIMENSIONS_TOO_LARGE -> ApiException.tooLarge("imagem_muito_grande", "A imagem tem dimensões grandes demais");
            case INVALID -> ApiException.unprocessable("imagem_invalida",
                    "Formato não suportado (envie PNG, JPEG, WebP ou GIF)");
            case ANIMATED -> ApiException.unprocessable("imagem_animada",
                    "Imagens animadas não são aceitas: para animações, envie um GIF");
            case GIF_INVALID -> ApiException.unprocessable("gif_invalido", "GIF inválido ou com quadros demais");
            case BUSY -> ApiException.unavailable("servidor_ocupado",
                    "Servidor ocupado processando imagens. Tente novamente em instantes.");
        };
    }
}
