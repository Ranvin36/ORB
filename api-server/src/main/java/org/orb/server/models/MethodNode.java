package org.orb.server.models;

import lombok.Getter;
import lombok.Setter;

/**
 * Represents a method node in the extracted graph.
 */

class MethodNode {
    @Getter
    @Setter
    private String id;
    @Getter
    @Setter
    private String className;
}

