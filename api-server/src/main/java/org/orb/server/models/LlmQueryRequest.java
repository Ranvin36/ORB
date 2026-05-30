package org.orb.server.models;

import lombok.Data;

@Data
public class LlmQueryRequest {
    private String userId;
    private String message;
    private boolean stream = false;
}
