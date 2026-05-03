package org.orb.server.models;

import lombok.Getter;
import lombok.Setter;

/**
 * Represents a method node in the extracted graph.
 */

public class MethodNode {
    @Getter
    @Setter
    private String id;
    @Getter
    @Setter
    private int startLine;
    @Getter
    @Setter
    private int endLine;
    @Getter
    @Setter
    private String className;
    @Getter
    @Setter
    private String filePath;
}

