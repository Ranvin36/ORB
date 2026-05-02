package org.orb.cli.models;

import lombok.Getter;
import lombok.Setter;

/**
 * Represents a directed call edge from one method node to another.
 */
public class CallEdge {
    @Getter
    @Setter
    private String from;

    @Getter
    @Setter
    private String to;

    @Getter
    @Setter
    private String type = "calls";
}

