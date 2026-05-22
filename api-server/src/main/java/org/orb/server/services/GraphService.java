package org.orb.server.services;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.orb.server.models.CallEdge;
import org.orb.server.models.ClassNode;
import org.orb.server.models.GraphPayload;
import org.orb.server.models.MethodNode;
import org.orb.server.models.SearchResult;
import org.orb.server.rabbitMQ.RabbitMQProducer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class GraphService {
    // Driver is injected from Neo4jConfig.java bean
    @Autowired
    private Driver driver;

    @Autowired
    private RabbitMQProducer rabbitMQProducer;

    private Map<String, ClassNode> classes = new HashMap<>();
    private Map<String, MethodNode> methods = new HashMap<>();
    private List<CallEdge> callEdges = new ArrayList<>();


    /**
     * Verifies that the Neo4j driver is connected and working.
     * 
     * @return true if connection is successful
     * @throws RuntimeException if connection fails
     */
    public boolean verifyConnection() {
        try {
            if (driver == null) {
                throw new RuntimeException("Neo4j Driver not initialized");
            }
            try (Session session = driver.session()) {
                session.run("RETURN 1").consume();
            }
            System.out.println("✓ Neo4j connection verified");
            return true;
        } catch (Exception e) {
            System.err.println("✗ Neo4j connection failed: " + e.getMessage());
            throw new RuntimeException("Cannot connect to Neo4j: " + e.getMessage(), e);
        }
    }

    /**
     * Loads graph data from the provided GraphPayload and populates the in-memory graph.
     *
     * @param graphPayload the payload containing classes, methods, and call edges
     */
    public void loadGraphFromPayload(GraphPayload graphPayload) {
        if (graphPayload == null || graphPayload.getNodes() == null) {
            throw new IllegalArgumentException("Invalid graph payload");
        }

        // Clear existing data
        classes.clear();
        methods.clear();
        callEdges.clear();

        // Load classes
        if (graphPayload.getNodes().getClasses() != null) {
            classes.putAll(graphPayload.getNodes().getClasses());
        }

        // Load methods
        if (graphPayload.getNodes().getMethods() != null) {
            methods.putAll(graphPayload.getNodes().getMethods());
        }

        // Load edges
        if (graphPayload.getEdges() != null) {
            callEdges.addAll(graphPayload.getEdges());
        }

        System.out.println("Graph loaded: " + classes.size() + " classes, " + 
                          methods.size() + " methods, " + callEdges.size() + " edges");
    }

    public void saveGraphToNeo4J() {
        if (driver == null) {
            throw new RuntimeException("Neo4j Driver is not initialized. Check your configuration.");
        }

        System.out.println("=====================================");
        System.out.println("Saving to Neo4j using Driver from Neo4jConfig");
        System.out.println("Driver class: " + driver.getClass().getName());
        System.out.println("=====================================");

        if (classes.isEmpty() && methods.isEmpty() && callEdges.isEmpty()) {
            System.out.println("Warning: No graph data to save (empty classes, methods, and edges)");
            return;
        }

        System.out.println("Starting graph save: " + classes.size() + " classes, " + 
                          methods.size() + " methods, " + callEdges.size() + " edges");

        List<Map<String, Object>> classRows = new ArrayList<>();
        List<Map<String, Object>> inheritanceRows = new ArrayList<>();
        List<Map<String, Object>> implementationRows = new ArrayList<>();
        Set<String> existingNodeIds = new HashSet<>();

        // First pass: create rows for classes
        for (ClassNode classNode : classes.values()) {
            String className = classNode.getName() == null ? "" : classNode.getName();
            String parentClass = classNode.getParentClass() == null ? "" : classNode.getParentClass();

            classRows.add(Map.of(
                    "id", className,
                    "properties", Map.of(
                            "kind", "class",
                            "name", className,
                            "filePath", classNode.getFilePath() == null ? "" : classNode.getFilePath(),
                            "type", classNode.getType() == null ? "" : classNode.getType(),
                            "parentClass", parentClass,
                            "implements", classNode.getImplement() == null ? List.of() : classNode.getImplement()
                    )
            ));
            rabbitMQProducer.sendNodeJob("class", className, classNode);
            existingNodeIds.add(className);

            if (!className.isBlank() && !parentClass.isBlank()) {
                inheritanceRows.add(Map.of(
                        "from", className,
                        "to", parentClass,
                        "properties", Map.of("type", "EXTENDS")
                ));
            }
        }

        // Second pass: ensure interface nodes exist and collect IMPLEMENTS edges
        for (ClassNode classNode : classes.values()) {
            String className = classNode.getName() == null ? "" : classNode.getName();
            List<String> implList = classNode.getImplement();
            if (implList == null) continue;
            for (String iface : implList) {
                if (iface == null || iface.isBlank()) continue;
                // If interface node not already represented, add it as an interface kind
                if (existingNodeIds.add(iface)) {
                    classRows.add(Map.of(
                            "id", iface,
                            "properties", Map.of(
                                    "kind", "interface",
                                    "name", iface,
                                    "filePath", "",
                                    "type", "",
                                    "parentClass", "",
                                    "implements", List.of()
                            )
                    ));
                }
                implementationRows.add(Map.of(
                        "from", className,
                        "to", iface,
                        "properties", Map.of("type", "IMPLEMENTS")
                ));
            }
        }

        List<Map<String, Object>> methodRows = new ArrayList<>();
        for (MethodNode methodNode : methods.values()) {
            String methodId = methodNode.getId() == null ? "" : methodNode.getId();
            methodRows.add(Map.of(
                    "id", methodId,
                    "classId", methodNode.getClassName() == null ? "" : methodNode.getClassName(),
                    "properties", Map.of(
                            "kind", "method",
                            "id", methodId,
                            "className", methodNode.getClassName() == null ? "" : methodNode.getClassName(),
                            "filePath", methodNode.getFilePath() == null ? "" : methodNode.getFilePath(),
                            "startLine", methodNode.getStartLine(),
                            "endLine", methodNode.getEndLine()
                    )
            ));
            rabbitMQProducer.sendNodeJob("method", methodId, methodNode);
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        for (CallEdge edge : callEdges) {
            edges.add(Map.of(
                    "from", edge.getFrom(),
                    "to", edge.getTo(),
                    "properties", Map.of("type", edge.getType() == null ? "calls" : edge.getType())
            ));
        }

        try (Session session = driver.session()) {
            // 1. Push classes to neo4j
            session.executeWrite(tx -> {
                String classQuery =
                        "UNWIND $classes AS node " +
                                "MERGE (n:Class {id: node.id}) " +
                                "SET n += node.properties";
                return tx.run(classQuery, Map.of("classes", classRows)).consume();
            });

            // 2. Push methods to neo4j & link them with classes
            session.executeWrite(tx -> {
                String methodQuery =
                        "UNWIND $methods AS method " +
                                "MERGE (m:Method {id: method.id}) " +
                                "SET m += method.properties " +
                                "WITH m, method " +
                                "WHERE method.classId <> '' " +
                                "MATCH (c:Class {id: method.classId}) " +
                                "MERGE (c)-[:HAS_METHOD]->(m)";
                return tx.run(methodQuery, Map.of("methods", methodRows)).consume();
            });

            // 3. Add inheritance edge
            if (!inheritanceRows.isEmpty()) {
                session.executeWrite(tx -> {
                    String inheritanceQuery =
                            "UNWIND $edges AS edge " +
                                    "MERGE (child:Class {id: edge.from}) " +
                                    "MERGE (parent:Class {id: edge.to}) " +
                                    "MERGE (child)-[r:EXTENDS]->(parent) " +
                                    "SET r += edge.properties";
                    return tx.run(inheritanceQuery, Map.of("edges", inheritanceRows)).consume();
                });
            }

            // 4. Add IMPLEMENTS edges (interfaces)
            if (!implementationRows.isEmpty()) {
                session.executeWrite(tx -> {
                    String implQuery =
                            "UNWIND $edges AS edge " +
                                    "MERGE (implClass:Class {id: edge.from}) " +
                                    "MERGE (iface:Class {id: edge.to}) " +
                                    "MERGE (implClass)-[r:IMPLEMENTS]->(iface) " +
                                    "SET r += edge.properties";
                    return tx.run(implQuery, Map.of("edges", implementationRows)).consume();
                });
            }


            // 5. Push the edges
            session.executeWrite(tx -> {
                String edgeQuery =
                        "UNWIND $edges AS edge " +
                                "MERGE (m1:Method {id: edge.from}) " +
                                "MERGE (m2:Method {id: edge.to}) " +
                                "MERGE (m1)-[r:CALLS]->(m2) " +
                                "SET r += edge.properties";
                return tx.run(edgeQuery, Map.of("edges", edges)).consume();
            });
        }
        catch (Exception e) {
            System.err.println("Error pushing graph to Neo4J: " + e.getMessage());
            System.err.println("Stack trace:");
            e.printStackTrace();
            throw new RuntimeException("Failed to save graph to Neo4j: " + e.getMessage(), e);
        }
        finally {
            System.out.println("Graph save process completed. Creating text index...");
            createTextIndex();
            System.out.println("Text Index Created For Nodes");
        }
    }

    public void createTextIndex() {
        try(Session session = driver.session()) {
            String indexQuery = "CREATE FULLTEXT INDEX codeSearch IF NOT EXISTS FOR (m:Class|Method) ON EACH [m.id, m.className, m.name, m.filePath]";
            session.run(indexQuery).consume();
            System.out.println("Text index created successfully on Method nodes for id, className, and filePath.");
        }
        catch (Exception e) {
            System.err.println("Error creating text index in neo4j: " + e.getMessage());
        }
    }

    public List<SearchResult> searchFromGraph(String searchComp) {
        List<SearchResult> searchResults = new ArrayList<>();
        try (Session session = driver.session()) {
            String fullTextQuery = """
                CALL db.index.fulltext.queryNodes("codeSearch", $searchText)
                YIELD node, score
                RETURN
                    coalesce(node.name, node.className, node.id, "Unknown") AS displayName,
                    node.id AS methodId,
                    node.filePath AS path,
                    coalesce(labels(node)[0], "Unknown") AS type,
                    score
                ORDER BY score DESC
            """;
            var result = session.run(fullTextQuery, Map.of("searchText", searchComp));

            while (result.hasNext()) {
                var record = result.next();
                SearchResult searchResult = new SearchResult();
                searchResult.setType(safeString(record, "type", "Unknown"));
                searchResult.setDisplayName(safeString(record, "displayName", "Unknown"));
                searchResult.setMethodId(safeString(record, "methodId", ""));
                searchResult.setPath(safeString(record, "path", ""));
                searchResult.setScore(safeDouble(record, "score", 0.0));
                searchResults.add(searchResult);
            }
        }
        catch (Exception e) {
            System.err.println("Error searching from neo4j graph " + e.getMessage());
            throw new RuntimeException("Failed to search graph: " + e.getMessage(), e);
        }
        return searchResults;
    }

    public int getComponentCount () {
        String query = "MATCH (n) RETURN count(n) AS componentCount";
        try (Session session = driver.session()) {
            var result = session.run(query);
            if (!result.hasNext()) {
                return 0;
            }
            int count = result.single().get("componentCount").asInt(0);
            System.out.println("Component count: " + count);
            return count;
        }
        catch (Exception e) {
            System.err.println("Error counting components in neo4j graph " + e.getMessage());
            throw new RuntimeException("Failed to count graph components: " + e.getMessage(), e);
        }
    }

    private String safeString(org.neo4j.driver.Record record, String field, String defaultValue) {
        if (record == null || record.get(field) == null || record.get(field).isNull()) {
            return defaultValue;
        }
        return record.get(field).asString(defaultValue);
    }

    private double safeDouble(org.neo4j.driver.Record record, String field, double defaultValue) {
        if (record == null || record.get(field) == null || record.get(field).isNull()) {
            return defaultValue;
        }
        return record.get(field).asDouble(defaultValue);

    }

    public int getRelationshipCount () {
        String query = "MATCH ()-[r]->() RETURN count(r) AS numberOfRelationships";
        try (Session session = driver.session()) {
            var result = session.run(query);
            if (!result.hasNext()) {
                return 0;
            }
            int count = result.single().get("numberOfRelationships").asInt(0);
            System.out.println("Component count: " + count);
            return count;
        }
        catch (Exception e) {
            System.err.println("Error counting components in neo4j graph " + e.getMessage());
            throw new RuntimeException("Failed to count graph components: " + e.getMessage(), e);
        }

    }

}
