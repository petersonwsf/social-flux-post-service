package com.socialflux.post.moderation;

/** O provedor de moderação não respondeu a tempo ou respondeu com erro. A mensagem nunca traz o conteúdo analisado. */
public class ModerationUnavailableException extends RuntimeException {

    public ModerationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
