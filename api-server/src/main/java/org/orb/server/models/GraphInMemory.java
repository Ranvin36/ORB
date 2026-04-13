package org.orb.server.models;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

import java.io.File;
import java.io.IOException;
import java.util.*;


/**
 * Stores extracted classes and methods in memory and exports them to JSON.
 */
public class GraphInMemory {
    private final Driver driver;
    private final Map<String, ClassNode> classes = new HashMap<>();
    private final Map<String, MethodNode> methods = new HashMap<>();
    private final List<CallEdge> callEdges = new ArrayList<>();
    private final Set<String> callEdgeKeys = new HashSet<>();

    public GraphInMemory(Driver driver) {
        this.driver = driver;
    }

    /**
     * Clears all class, method, and call edge nodes from the in-memory graph.
     */
    public void resetGraph() {
        classes.clear();
        methods.clear();
        callEdges.clear();
        callEdgeKeys.clear();
    }

    /**
     * Builds a unique method identifier.
     *
     * @param className  class that owns the method
     * @param methodName method name
     * @return class-qualified id in the form {@code Class.method}, or method name
     *         when class is blank
     */
    private String buildMethodId(String className, String methodName) {
        if (className == null || className.isBlank()) {
            return methodName;
        }
        return className + "." + methodName;
    }

    /**
     * Adds a class node when it does not already exist.
     *
     * @param className class name to index
     */
    public void addClassNode(String filePath, String className, String type, List<String> implementedInterfaces,
            List<String> extendedClasses) {
        classes.compute(className, (nodeName, existingNode) -> {
            ClassNode classNode = existingNode == null ? new ClassNode() : existingNode;
            classNode.setName(className);
            classNode.setType(type);
            classNode.setFilePath(filePath);
            if (implementedInterfaces == null || implementedInterfaces.isEmpty()) {
                classNode.setImplement(new ArrayList<>());
            } else {
                classNode.setImplement(new ArrayList<>(new LinkedHashSet<>(implementedInterfaces)));
            }
            if (extendedClasses == null || extendedClasses.isEmpty()) {
                classNode.setParentClass("");
            } else {
                classNode.setParentClass(extendedClasses.getLast());
            }
            return classNode;
        });
    }

    /**
     * Adds a method node and returns its resolved method id.
     *
     * @param methodName method name to index
     * @param className  owning class name
     * @return resolved method id used as map key
     */
    public String addMethodNode(int startLine, int endLine, String filePath, String methodName, String className) {
        String methodId = buildMethodId(className, methodName);
        methods.computeIfAbsent(methodId, name -> {
            MethodNode methodNode = new MethodNode();
            methodNode.setClassName(className);
            methodNode.setFilePath(filePath);
            methodNode.setStartLine(startLine);
            methodNode.setEndLine(endLine);
            methodNode.setId(methodId);
            return methodNode;
        });
        return methodId;
    }

    /**
     * Adds a directed method call edge from one method id to another.
     *
     * @param methodId   caller method id
     * @param methodCall callee method id
     */
    public void addMethodCall(String methodId, String methodCall) {
        if (methodId == null || methodId.isBlank() || methodCall == null || methodCall.isBlank()) {
            return;
        }

        methods.computeIfAbsent(methodId, name -> {
            MethodNode methodNode = new MethodNode();
            methodNode.setId(methodId);
            return methodNode;
        });

        String edgeKey = methodId + "->" + methodCall;
        if (!callEdgeKeys.add(edgeKey)) {
            return;
        }

        CallEdge callEdge = new CallEdge();
        callEdge.setFrom(methodId);
        callEdge.setTo(methodCall);
        callEdges.add(callEdge);
    }

    /**
     * Serializes the in-memory graph and writes it to {@code graph.json}.
     */
    public void writeToJson() throws IOException {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("classes", classes);
        nodes.put("methods", methods);

        Map<String, Object> combinedGraphInMemory = new LinkedHashMap<>();
        combinedGraphInMemory.put("nodes", nodes);
        combinedGraphInMemory.put("edges", callEdges);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File("graph.json"), combinedGraphInMemory);
    }


    public void pushToNeo4J() {
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
                            "type", classNode.getType() == null ? "" : classNode.getType(),
                            "parentClass", parentClass,
                            "implements", classNode.getImplement() == null ? List.of() : classNode.getImplement()
                    )
            ));
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
            methodRows.add(Map.of(
                    "id", methodNode.getId(),
                    "classId", methodNode.getClassName() == null ? "" : methodNode.getClassName(),
                    "properties", Map.of(
                            "kind", "method",
                            "id", methodNode.getId(),
                            "className", methodNode.getClassName() == null ? "" : methodNode.getClassName()
                    )
            ));
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
        }
    }
}
