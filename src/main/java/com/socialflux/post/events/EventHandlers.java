package com.socialflux.post.events;

import com.socialflux.events.EventEnvelope;
import com.socialflux.events.Inbox;
import com.socialflux.post.service.FollowSyncService;
import com.socialflux.post.service.ProfileSyncService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reage aos eventos consumidos ({@code profile.*} e {@code follow.*}). Idempotente (inbox por id de mensagem) e tolerante a
 * tipos desconhecidos; a lógica está em {@link ProfileSyncService} e {@link FollowSyncService}. Os testes chamam estes
 * métodos direto, exatamente como os listeners do RabbitMQ.
 */
@Component
public class EventHandlers {

    private final ProfileSyncService profiles;
    private final FollowSyncService follows;
    private final Inbox inbox;
    private final JsonMapper mapper;

    public EventHandlers(ProfileSyncService profiles, FollowSyncService follows, Inbox inbox, JsonMapper mapper) {
        this.profiles = profiles;
        this.follows = follows;
        this.inbox = inbox;
        this.mapper = mapper;
    }

    public void handleProfile(String json) {
        EventEnvelope event = EventEnvelope.parse(mapper, json);
        inbox.once(event, () -> profiles.apply(event));
    }

    public void handleFollow(String json) {
        EventEnvelope event = EventEnvelope.parse(mapper, json);
        inbox.once(event, () -> follows.apply(event));
    }
}
