package org.orb.server.controllers;

import org.orb.server.models.Neo4jQueryRequest;
import org.orb.server.services.GraphService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/neo4j")
public class Neo4jController {

    @Autowired
    private GraphService graphService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(graphService.healthCheck());
    }

    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        return ResponseEntity.ok(graphService.ping());
    }

    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> query(@RequestBody Neo4jQueryRequest payload) {
        try {
            List<Map<String, Object>> rows = graphService.executeQuery(payload.getCypher(), payload.getParams());
            return ResponseEntity.ok(Map.of(
                "count", rows.size(),
                "rows", rows
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
