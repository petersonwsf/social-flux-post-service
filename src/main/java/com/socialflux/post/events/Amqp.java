package com.socialflux.post.events;

import com.socialflux.events.EventQueues;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Filas de consumo deste serviço. O exchange {@code topic} compartilhado ({@code socialflux.events}) e a fila de mensagens
 * mortas são declarados pela biblioteca {@code socialflux-events}; aqui só as filas de Postagens e as ligações.
 */
@Configuration
@ConditionalOnProperty(prefix = "socialflux.events", name = "enabled", havingValue = "true")
public class Amqp {

    public static final String PROFILE_QUEUE = "post-service.profile-events";
    public static final String FOLLOW_QUEUE = "post-service.follow-events";

    /** Perfis dos autores (criação, nome/foto, privacidade, exclusão) publicados pelo profile_service. */
    @Bean
    Queue profileQueue() {
        return EventQueues.durable(PROFILE_QUEUE);
    }

    @Bean
    Binding profileBinding(@Qualifier("profileQueue") Queue profileQueue,
                           @Qualifier(EventQueues.EXCHANGE_BEAN) TopicExchange exchange) {
        return EventQueues.bind(profileQueue, exchange, "profile.#");
    }

    /** Relações aceitas (seguir/deixar de seguir) publicadas pelo social_graph_service: feed e privacidade. */
    @Bean
    Queue followQueue() {
        return EventQueues.durable(FOLLOW_QUEUE);
    }

    @Bean
    Binding followBinding(@Qualifier("followQueue") Queue followQueue,
                          @Qualifier(EventQueues.EXCHANGE_BEAN) TopicExchange exchange) {
        return EventQueues.bind(followQueue, exchange, "follow.#");
    }
}
