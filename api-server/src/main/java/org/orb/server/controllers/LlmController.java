package org.orb.server.controllers;

import dev.langchain4j.service.TokenStream;
import org.orb.server.models.LlmQueryRequest;
import org.orb.server.services.RagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/llm")
public class LlmController {

    @Autowired
    private RagService ragService;

    @PostMapping("/stream")
    public Object query(@RequestBody LlmQueryRequest payload) {
        if (payload.isStream()) {
            System.out.println("Received streaming request: " + payload.getMessage());
            // Increase timeout to 3 minutes
            SseEmitter emitter = new SseEmitter(180000L);
            
            TokenStream tokenStream = ragService.askStreaming(payload.getMessage());

            tokenStream
                .onNext(token -> {
                    try {
                        System.out.println("Sending token: " + token);
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException e) {
                        System.err.println("Error sending token: " + e.getMessage());
                        emitter.completeWithError(e);
                    }
                })
                .onComplete(c -> {
                    try {
                        System.out.println("Streaming complete");
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        emitter.complete();
                    } catch (IOException e) {
                        System.err.println("Error completing stream: " + e.getMessage());
                        emitter.completeWithError(e);
                    }
                })
                .onError(error -> {
                    System.err.println("Streaming error: " + error.getMessage());
                    error.printStackTrace();
                    emitter.completeWithError(error);
                })
                .start();

            return emitter;
        } else {
            System.out.println("Received blocking request: " + payload.getMessage());
            String answer = ragService.ask(payload.getMessage());
            return Map.of("answer", answer);
        }
    }
}
