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
            SseEmitter emitter = new SseEmitter();
            TokenStream tokenStream = ragService.askStreaming(payload.getMessage());

            tokenStream
                .onNext(token -> {
                    try {
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .onComplete(c -> {
                    try {
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .onError(error -> {
                    emitter.completeWithError(error);
                })
                .start();

            return emitter;
        } else {
            String answer = ragService.ask(payload.getMessage());
            return Map.of("answer", answer);
        }
    }
}
