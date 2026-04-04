package org.orb.server.models;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

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

