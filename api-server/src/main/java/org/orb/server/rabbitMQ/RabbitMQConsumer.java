package org.orb.server.rabbitMQ;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RabbitMQConsumer {

    @RabbitListener(queues = "${rag.rabbitmq.queue:summarization-queue}")
    public void consume(String message) {
        log.info("RabbitMQ message received: body={}", message);
        // Logic for message processing can be added here
        // The original Python code had a NotImplementedError for processing
    }
}
