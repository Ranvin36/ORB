package org.orb.server.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the JSON payload received from the client containing graph data.
 * Structure: { "nodes": { "classes": {...}, "methods": {...} }, "edges": [...] }
 */
@Getter
@Setter
public class GraphPayload {
    @JsonProperty("nodes")
    private GraphNodes nodes;

    @JsonProperty("edges")
    private List<CallEdge> edges;

    @Getter
    @Setter
    public static class GraphNodes {
        @JsonProperty("classes")
        private Map<String, ClassNode> classes = new HashMap<>();

        @JsonProperty("methods")
        private Map<String, MethodNode> methods = new HashMap<>();
    }
}

