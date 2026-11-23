package com.socialflux.post.events;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Listeners do RabbitMQ: só convertem a mensagem em texto e delegam ao {@link EventHandlers}. */
@Component
@ConditionalOnProperty(prefix = "socialflux.events", name = "enabled", havingValue = "true")
public class EventListeners {

    private final EventHandlers handlers;

    public EventListeners(EventHandlers handlers) {
        this.handlers = handlers;
    }

    @RabbitListener(queues = Amqp.PROFILE_QUEUE)
    void onProfile(Message message) {
        handlers.handleProfile(new String(message.getBody(), StandardCharsets.UTF_8));
    }

    @RabbitListener(queues = Amqp.FOLLOW_QUEUE)
    void onFollow(Message message) {
        handlers.handleFollow(new String(message.getBody(), StandardCharsets.UTF_8));
    }
}
