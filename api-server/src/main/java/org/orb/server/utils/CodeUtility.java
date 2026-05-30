package org.orb.server.utils;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class CodeUtility {

    /**
     * Reads a snippet of code from a file given the start and end lines.
     *
     * @param filePath  the path to the file
     * @param startLine the starting line number (1-indexed)
     * @param endLine   the ending line number (inclusive, 1-indexed)
     * @return the code snippet as a string
     */
    public String getCodeSnippet(String filePath, int startLine, int endLine) {
        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                // Try normalizing path if for some reason it's different on the FS
                path = Paths.get(filePath.replace("\\", "/"));
            }

            if (!Files.exists(path)) {
                return "Error: File not found at " + filePath;
            }

            List<String> lines = Files.readAllLines(path);
            int startIdx = Math.max(0, startLine - 1);
            int endIdx = Math.min(lines.size(), endLine);

            if (startIdx >= lines.size()) {
                return "";
            }

            return lines.subList(startIdx, endIdx).stream()
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }
}
