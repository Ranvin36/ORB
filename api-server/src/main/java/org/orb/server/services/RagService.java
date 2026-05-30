package org.orb.server.services;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;
import org.orb.server.utils.CodeUtility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

@Slf4j
@Service
public class RagService {

    @Autowired
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private StreamingChatLanguageModel streamingChatLanguageModel;

    @Autowired
    private GraphService graphService;

    @Autowired
    private CodeUtility codeUtility;

    private Assistant assistant;
    private StreamingAssistant streamingAssistant;

    public interface Assistant {
        @SystemMessage("""
            You are a code intelligence assistant. Use the following graph schema to answer user queries:

            ### Node Labels and Properties:
            (:Class {name: string, type: string, parentClass: string, filePath: string})
            (:Method {id: string, className: string, kind: string, startLine: int, endLine: int, filePath: string})

            ### Relationship Types:
            (:Method)-[:CALLS]->(:Method)
            (:Class)-[:EXTENDS]->(:Class)
            (:Class)-[:IMPLEMENTS]->(:Class)
            (:Class)-[:HAS_METHOD]->(:Method)

            ### Important:
            When you need to explain code, ALWAYS alias your Cypher RETURN fields exactly as:
            filePath, startLine, endLine

            ### CRITICAL QUERYING INSTRUCTIONS:
            1. **Case-Insensitive Matching**: Always use `toLower()` for string comparisons (e.g., `WHERE toLower(m.id) = toLower($methodId)`).
            2. **Composite Names (e.g., 'Main.main')**:
               - The `id` property for a Method node is typically in the format "ClassName.methodName".
               - To find a method like 'Main.main', you should query directly on the `id` property.
               - Example: `MATCH (m:Method) WHERE toLower(m.id) = toLower('Main.main') RETURN m.filePath AS filePath, m.startLine AS startLine, m.endLine AS endLine`
               - If you need to find methods within a specific class, you can combine `className` and a partial match on `id` or use `className` directly.
               - Example: `MATCH (m:Method) WHERE toLower(m.className) = toLower('Main') AND toLower(m.id) CONTAINS toLower('.main') RETURN m.filePath AS filePath, m.startLine AS startLine, m.endLine AS endLine`
            3. **Code Retrieval**: When explaining code, ALWAYS return aliased fields exactly as filePath, startLine, endLine.

            Workflow:
            1. Query Neo4j for structural context.
            2. The system will automatically fetch code snippets if you return 'filePath', 'startLine', and 'endLine' with exact aliases (e.g., m.filePath AS filePath).
            3. Explain the code using both graph and source context.""")
        String chat(String message);
    }

    public interface StreamingAssistant {
        @SystemMessage("""
            You are a code intelligence assistant. Use the following graph schema to answer user queries:

            ### Node Labels and Properties:
            (:Class {name: string, type: string, parentClass: string, filePath: string})
            (:Method {id: string, className: string, kind: string, startLine: int, endLine: int, filePath: string})

            ### Relationship Types:
            (:Method)-[:CALLS]->(:Method)
            (:Class)-[:EXTENDS]->(:Class)
            (:Class)-[:IMPLEMENTS]->(:Class)
            (:Class)-[:HAS_METHOD]->(:Method)

            ### Important:
            When you need to explain code, ALWAYS alias your Cypher RETURN fields exactly as:
            filePath, startLine, endLine

            ### CRITICAL QUERYING INSTRUCTIONS:
            1. **Case-Insensitive Matching**: Always use `toLower()` for string comparisons (e.g., `WHERE toLower(m.id) = toLower($methodId)`).
            2. **Composite Names (e.g., 'Main.main')**:
               - The `id` property for a Method node is typically in the format "ClassName.methodName".
               - To find a method like 'Main.main', you should query directly on the `id` property.
               - Example: `MATCH (m:Method) WHERE toLower(m.id) = toLower('Main.main') RETURN m.filePath AS filePath, m.startLine AS startLine, m.endLine AS endLine`
               - If you need to find methods within a specific class, you can combine `className` and a partial match on `id` or use `className` directly.
               - Example: `MATCH (m:Method) WHERE toLower(m.className) = toLower('Main') AND toLower(m.id) CONTAINS toLower('.main') RETURN m.filePath AS filePath, m.startLine AS startLine, m.endLine AS endLine`
            3. **Code Retrieval**: When explaining code, ALWAYS return aliased fields exactly as filePath, startLine, endLine.

            Workflow:
            1. Query Neo4j for structural context.
            2. The system will automatically fetch code snippets if you return 'filePath', 'startLine', and 'endLine' with exact aliases (e.g., m.filePath AS filePath).
            3. Explain the code using both graph and source context.""")
        TokenStream chat(String message);
    }

    @PostConstruct
    public void init() {
        GraphTool graphTool = new GraphTool(graphService, codeUtility);

        this.assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(chatLanguageModel)
                .tools(graphTool)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        this.streamingAssistant = AiServices.builder(StreamingAssistant.class)
                .streamingChatLanguageModel(streamingChatLanguageModel)
                .tools(graphTool)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();
    }

    public String ask(String message) {
        return assistant.chat(message);
    }

    public TokenStream askStreaming(String message) {
        return streamingAssistant.chat(message);
    }

    public static class GraphTool {
        private final GraphService graphService;
        private final CodeUtility codeUtility;

        public GraphTool(GraphService graphService, CodeUtility codeUtility) {
            this.graphService = graphService;
            this.codeUtility = codeUtility;
        }

        @Tool("Retrieve the relevant subgraph context from Neo4j for the user query. " +
              "Return read-only Cypher that fetches connected nodes, relationships, " +
              "and multi-hop paths needed to answer the question.")
        public String queryNeo4j(String cypher) {
            try {
                List<Map<String, Object>> rows = graphService.executeQuery(cypher, new HashMap<>());
                List<Map<String, Object>> enrichedRows = new ArrayList<>();

                for (Map<String, Object> row : rows) {
                    Map<String, Object> enrichedRow = new HashMap<>(row);
                    extractAndEnrichCode(enrichedRow);
                    enrichedRows.add(enrichedRow);
                }

                Map<String, Object> result = new HashMap<>();
                result.put("count", enrichedRows.size());
                result.put("rows", enrichedRows);

                if (enrichedRows.isEmpty()) {
                    result.put("hint", "No results found. Try a case-insensitive search with toLower() or use CONTAINS for partial matches.");
                }

                // Convert to simple JSON-like string (or use Jackson)
                return result.toString();
            } catch (Exception e) {
                return "Error: " + e.getMessage() + ". Hint: Fix the Cypher and try query_neo4j again.";
            }
        }

        private void extractAndEnrichCode(Map<String, Object> row) {
            String path = getRowValue(row, "filePath", "path");
            Object startLineObj = getRowValue(row, "startLine", "start_line");
            Object endLineObj = getRowValue(row, "endLine", "end_line");

            if (path != null && startLineObj != null && endLineObj != null) {
                try {
                    int startLine = Integer.parseInt(startLineObj.toString());
                    int endLine = Integer.parseInt(endLineObj.toString());
                    String snippet = codeUtility.getCodeSnippet(path, startLine, endLine);
                    row.put("code_content", snippet);
                } catch (NumberFormatException ignored) {}
            }
        }

        private String getRowValue(Map<String, Object> row, String... keys) {
            for (String key : keys) {
                Object val = row.get(key);
                if (val != null) return val.toString();
            }
            return null;
        }
    }
}
