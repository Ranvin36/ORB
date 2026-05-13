package org.orb.server.models;

public class SearchResult {
    private String type;
    private String displayName;
    private String methodId;
    private String path;
    private double score;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getMethodId() {
        return methodId;
    }

    public void setMethodId(String methodId) {
        this.methodId = methodId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }
}

