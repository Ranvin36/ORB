package org.orb.server.rabbitMQ;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.orb.server.configs.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;


@Service
public class RabbitMQProducer {

    private final RabbitTemplate rabbitTemplate;

    @Autowired
    public RabbitMQProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void sendMessage(String message) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, RabbitMQConfig.ROUTING_KEY, message);
        System.out.println("Sending message: " + message);
    }

    public void sendNodeJob(String nodeType, String nodeKey, Object node) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("jobType", "NODE_SUMMARY");
        job.put("nodeType", nodeType);
        job.put("nodeKey", nodeKey);
        job.put("createdAt", Instant.now().toString());
        job.put("node", node);

        try {
            String message = new ObjectMapper().writeValueAsString(job);
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, RabbitMQConfig.ROUTING_KEY, message);
            System.out.println("Sending summary job: " + message);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize summary job payload", e);
        }
    }
}
