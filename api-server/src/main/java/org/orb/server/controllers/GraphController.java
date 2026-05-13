package org.orb.server.controllers;

import org.orb.server.models.GraphPayload;
import org.orb.server.models.Repository;
import org.orb.server.models.SearchResult;
import org.orb.server.services.GraphService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

@RestController
@RequestMapping("/graph")
public class GraphController {

    @Autowired
    private GraphService graphService;

    /**
     * Tests the Neo4j database connection.
     * 
     * @return connection status with details
     */
    @GetMapping("/neo4j/test")
    public ResponseEntity<String> testNeo4jConnection() {
        try {
            graphService.verifyConnection();
            return ResponseEntity.ok("✓ Neo4j connection successful");
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body("✗ Neo4j connection failed: " + e.getMessage());
        }
    }

    /**
     * Accepts a JSON file containing graph data (classes, methods, edges) from the client,
     * extracts the data, and saves it to Neo4j.
     *
     * @param graphPayload the JSON payload containing nodes (classes/methods) and edges
     * @return success message with count of extracted entities
     */
    @PostMapping("/upload")
    @ResponseBody
    public ResponseEntity<String> uploadGraph(@RequestBody GraphPayload graphPayload) {
        System.out.println("uploadGraph");
        try {
            graphService.loadGraphFromPayload(graphPayload);
            graphService.saveGraphToNeo4J();
            return ResponseEntity.ok("Graph successfully uploaded and processed. " +
                    "Classes: " + graphPayload.getNodes().getClasses().size() + ", " +
                    "Methods: " + graphPayload.getNodes().getMethods().size() + ", " +
                    "Edges: " + graphPayload.getEdges().size());
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body("Error processing graph: " + e.getMessage());
        }
    }

    @PostMapping("/save")
    @ResponseBody
    public String saveGraph(@RequestBody Repository repository) throws IOException {
        graphService.saveGraphToNeo4J();
        return "Indexing process started for repository: ";
    }

    @GetMapping("/search")
    public ResponseEntity<java.util.List<SearchResult>> searchGraph(@RequestParam String query) {
        return ResponseEntity.ok(graphService.searchFromGraph(query));
    }

    @GetMapping("/components-count")
    public ResponseEntity<Integer> componentsCount() {
        return ResponseEntity.ok(graphService.getComponentCount());
    }

    @GetMapping("/relationship-count")
    public ResponseEntity<Integer> relationshipCount() {
        return ResponseEntity.ok(graphService.getRelationshipCount());
    }


    @GetMapping("/connection")
    public ResponseEntity<String> testConnection() {
        return ResponseEntity.ok("api-server is healthy");
    }
}
