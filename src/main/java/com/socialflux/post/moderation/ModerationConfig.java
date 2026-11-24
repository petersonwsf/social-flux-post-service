package com.socialflux.post.moderation;

import com.socialflux.post.config.PostProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Escolhe o moderador: o da OpenAI quando ligado (exige a chave; falha ao subir sem ela) ou o que aprova tudo. */
@Configuration
class ModerationConfig {

    @Bean
    ContentModerator contentModerator(PostProperties properties) {
        PostProperties.Moderation moderation = properties.moderation();
        if (!moderation.enabled()) {
            return new NoopContentModerator();
        }
        if (moderation.apiKey() == null || moderation.apiKey().isBlank()) {
            throw new IllegalStateException("post.moderation.enabled=true exige POST_MODERATION_API_KEY");
        }
        return new OpenAiContentModerator(moderation, new ModerationPolicy(moderation.thresholds()));
    }
}
