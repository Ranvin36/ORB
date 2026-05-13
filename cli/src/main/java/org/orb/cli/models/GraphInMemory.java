package org.orb.cli.models;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Stores extracted classes and methods in memory and exports them to JSON.
 */
public class GraphInMemory {
    private final Map<String, ClassNode> classes = new HashMap<>();
    private final Map<String, MethodNode> methods = new HashMap<>();
    private final List<CallEdge> callEdges = new ArrayList<>();
    private final Set<String> callEdgeKeys = new HashSet<>();

    public GraphInMemory() {
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
     * Serializes the in-memory graph and writes it to the user's home directory under orb/graph.json.
     */
    public void writeToJson() throws IOException {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("classes", classes);
        nodes.put("methods", methods);

        Map<String, Object> combinedGraphInMemory = new LinkedHashMap<>();
        combinedGraphInMemory.put("nodes", nodes);
        combinedGraphInMemory.put("edges", callEdges);

        String userHome = System.getProperty("user.home");
        File outputDir = new File(userHome, ".orb");
        outputDir.mkdirs();

        File outputFile = new File(outputDir, "graph.json");
        System.out.println("Saving to json");
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, combinedGraphInMemory);
        System.out.println("Graph saved to: " + outputFile.getAbsolutePath());
    }


    public void sendGraphToServer() {
        File graphPath = new File(System.getProperty("user.home"),".orb/graph.json");
        if (!graphPath.exists()) {
            System.err.println("Error: Graph JSON file not found at " + graphPath);
            return;
        }

        try ( HttpClient httpClient = HttpClient.newHttpClient()) {
            String uploadUrl = resolveUploadUrl();
            if (uploadUrl == null || uploadUrl.isBlank()) {
                System.err.println("Error: Upload URL is not configured. Set 'upload.url' or 'upload_url' in application.properties");
                return;
            }
            System.out.println("Sending graph JSON to server to: " + uploadUrl);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(uploadUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofFile(graphPath.toPath()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("Response status: " + response.statusCode());
            System.out.println("Response headers: " + response.headers().map());
            System.out.println("Response body: " + response.body());

        } catch (Exception e) {
            System.err.println("Error sending graph to server: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    // Simplified: load `application.properties` from classpath and return the configured URL.
    // No fallbacks or additional validation are performed — caller will receive null if not found.
    private String resolveUploadUrl() {
        try (InputStream is = GraphInMemory.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (is == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(is);
            String v = props.getProperty("upload.url");
            if (v != null) return v;
            return props.getProperty("upload_url");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
