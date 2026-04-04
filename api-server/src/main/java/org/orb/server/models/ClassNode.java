package org.orb.server.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a class node in the extracted graph.
 */

public class ClassNode {
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
