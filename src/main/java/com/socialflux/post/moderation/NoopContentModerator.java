package com.socialflux.post.moderation;

/** Moderador desligado ({@code post.moderation.enabled=false}): aprova tudo. */
public class NoopContentModerator implements ContentModerator {

    @Override
    public Verdict checkText(String text) {
        return Verdict.ALLOWED;
    }
}
