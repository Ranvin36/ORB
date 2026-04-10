package org.orb.server.services.scanning;

import lombok.Getter;
import lombok.Setter;

import java.util.*;

public class FileMetadata {
    @Getter
    @Setter
    String packageName;

    @Getter
    Map<String, String> explicitTypeImports;
    @Getter
    Set<String> wildcardImports;
    @Getter
    Map<String, String> staticMemberImports;
    @Getter
    Set<String> staticWildcardImports;
    @Getter
    Set<String> localTypeNames;

    /**
     * Initializes all import and metadata collections.
     */
    public FileMetadata() {
        this.explicitTypeImports = new HashMap<>();
        this.wildcardImports = new HashSet<>();
        this.staticMemberImports = new HashMap<>();
        this.staticWildcardImports = new HashSet<>();
        this.localTypeNames = new HashSet<>();
    }
}
