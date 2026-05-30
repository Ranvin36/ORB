package org.orb.server.models;

import lombok.Data;
import java.util.Map;
import java.util.HashMap;

@Data
public class Neo4jQueryRequest {
    private String cypher;
    private Map<String, Object> params = new HashMap<>();
}
