package org.orb.server.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Represents a class node in the extracted graph.
 */
class ClassNode {
    @Getter
    @Setter
    private String name;
    @Getter
    @Setter
    private String type;
    @Getter
    @Setter
    private String parentClass;
    @Getter
    @Setter
    @JsonProperty("implements")
    private List<String> implement = new ArrayList<>();
}

/**
 * Represents a method node and the invocations made from that method.
 */
class MethodNode {
    @Getter
    @Setter
    private String id;
    @Getter
    @Setter
    private String className;
    @Setter
    @Getter
    private List<String> calls = new ArrayList<String>();
}

/**
 * Stores extracted classes and methods in memory and exports them to JSON.
 */
public class GraphInMemory {
    private Map<String, ClassNode> classes = new HashMap<String, ClassNode>();
    private Map<String, MethodNode> methods = new HashMap<String, MethodNode>();

    /**
     * Clears all class and method nodes from the in-memory graph.
     */
    public void resetGraph() {
        classes.clear();
        methods.clear();
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
                classNode.setParentClass(extendedClasses.get(extendedClasses.size() - 1));
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
     * Adds a method invocation to the given method node.
     * Creates a placeholder method node when the id is not present.
     *
     * @param methodId   source method id
     * @param methodCall invocation text (for example {@code service.add})
     */
    public void addMethodCall(String methodId, String methodCall) {
        MethodNode methodNode = methods.computeIfAbsent(methodId, node -> {
            MethodNode methodNode1 = new MethodNode();
            methodNode1.setId(methodId);
            return methodNode1;
        });
        methodNode.getCalls().add(methodCall);
    }

    /**
     * Serializes the in-memory graph and writes it to {@code graph.json}.
     * Also prints the generated JSON to standard output.
     */
    public void writeToJson() throws IOException {
        Map<String, Object> combinedGraphInMemory = new HashMap<>();
        combinedGraphInMemory.put("methods", methods);
        combinedGraphInMemory.put("classes", classes);
        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(combinedGraphInMemory);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(new File("graph.json"), combinedGraphInMemory);
        System.out.println(json);
    }

}
