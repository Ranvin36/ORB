package org.orb.server.services.scanning;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.Set;

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
}
