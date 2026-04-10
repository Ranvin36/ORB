package org.orb.server.models;


import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.*;


/**
 * Stores extracted classes and methods in memory and exports them to JSON.
 */
public class GraphInMemory {
    private final Map<String, ClassNode> classes = new HashMap<>();
    private final Map<String, MethodNode> methods = new HashMap<>();
    private final List<CallEdge> callEdges = new ArrayList<>();
    private final Set<String> callEdgeKeys = new HashSet<>();

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
    public void addClassNode(String className, String type, List<String> implementedInterfaces,
            List<String> extendedClasses) {
        classes.compute(className, (nodeName, existingNode) -> {
            ClassNode classNode = existingNode == null ? new ClassNode() : existingNode;
            classNode.setName(className);
            classNode.setType(type);
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
    public String addMethodNode(String methodName, String className) {
        String methodId = buildMethodId(className, methodName);
        methods.computeIfAbsent(methodId, name -> {
            MethodNode methodNode = new MethodNode();
            methodNode.setClassName(className);
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

}
