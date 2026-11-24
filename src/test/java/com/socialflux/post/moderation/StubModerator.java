package com.socialflux.post.moderation;

import java.util.function.Function;

/** Moderador de teste: cada método devolve o que a função recebida decidir (ou lança, para simular o provedor fora do ar). */
record StubModerator(Function<String, Verdict> text, Function<ModerationImage, Verdict> image) implements ContentModerator {

    static StubModerator forText(Function<String, Verdict> text) {
        return new StubModerator(text, i -> Verdict.ALLOWED);
    }

    static StubModerator forImages(Function<ModerationImage, Verdict> image) {
        return new StubModerator(t -> Verdict.ALLOWED, image);
    }

    @Override
    public Verdict checkText(String value) {
        return text.apply(value);
    }

    @Override
    public Verdict checkImage(ModerationImage value) {
        return image.apply(value);
    }
}
