package org.orb.server.configs;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    final static String ROUTING_KEY = "nodes";
    final static String EXCHANGE_NAME = "graph-exchange";
    final static String QUEUE_NAME = "summarization-queue";

    @Bean
    public TopicExchange topicExchange() {
        return new TopicExchange(EXCHANGE_NAME);
    }

    @Bean
    public Queue queue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public Binding binding(TopicExchange topicExchange, Queue queue) {
        return BindingBuilder.bind(queue).to(topicExchange).with(ROUTING_KEY);
    }

}
