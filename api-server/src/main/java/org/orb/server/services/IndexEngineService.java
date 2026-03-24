package org.orb.server.services;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.orb.server.models.GraphInMemory;
import org.springframework.stereotype.Service;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJava;

@Service
public class IndexEngineService {
    private GraphInMemory graphInMemory;
    private Set<String> knownMethodIds;

    /**
     * Creates a new indexing service with an empty in-memory graph.
     */
    public IndexEngineService() {
        this.graphInMemory =  new GraphInMemory();
        this.knownMethodIds = new HashSet<>();
    }

    /**
     * Locates the repository directory under the user's Documents folder.
     * Returns an Optional containing the Path if the directory exists and contains at least one entry.
     * Returns Optional.empty() if not found or on invalid input.
     *
     * @param repositoryName repository folder name to search under Documents/orb
     * @return optional path to the repository when found and non-empty
     * @throws IOException if directory access fails while checking repository contents
     */
    public Optional<Path> locateRepositoryInFileSystem(String repositoryName) throws IOException {
        if (repositoryName == null || repositoryName.isBlank()) {
            return Optional.empty();
        }

        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return Optional.empty();
        }

        Path repoPath = Path.of(userHome, "Documents","orb",repositoryName);

        if (Files.exists(repoPath) && Files.isDirectory(repoPath)) {
            // Use DirectoryStream to avoid leaving an open stream
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(repoPath)) {
                if (ds.iterator().hasNext()) {
                    return Optional.of(repoPath);
                } else {
                    // directory exists but is empty -> treat as not found (matches original behavior)
                    return Optional.empty();
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Extracts source text for a tree-sitter node using byte offsets.
     *
     * @param node tree node whose text span should be read
     * @param source full source text that was parsed
     * @return exact source slice represented by the provided node
     */
    private String getNodeText(TSNode node, String source){
        int startingByte  = node.getStartByte();
        int endingByte = node.getEndByte();
        return source.substring(startingByte,endingByte);
    }

    /**
     * Tries to resolve a declaration type node across common Java grammar variants.
     *
     * @param declaration declaration node
     * @return type node when found, otherwise null
     */
    private TSNode getTypeNode(TSNode declaration) {
        TSNode typeNode = declaration.getChildByFieldName("type");
        if (typeNode != null) {
            return typeNode;
        }

        for (int i = 0; i < declaration.getChildCount(); i++) {
            TSNode child = declaration.getChild(i);
            String childType = child.getType();
            if ("type_identifier".equals(childType)
                    || "scoped_type_identifier".equals(childType)
                    || "generic_type".equals(childType)
                    || childType.endsWith("_type")) {
                return child;
            }
        }

        return null;
    }

    /**
     * Tries to resolve an identifier child by field name, then by scanning children.
     *
     * @param node node containing an identifier
     * @param fieldName preferred field name
     * @return identifier node when found, otherwise null
     */
    private TSNode getIdentifierNode(TSNode node, String fieldName) {
        TSNode idNode = node.getChildByFieldName(fieldName);
        if (idNode != null) {
            return idNode;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if ("identifier".equals(child.getType())) {
                return child;
            }
        }

        return null;
    }

    /**
     * Adds all declared variables from a declaration node to the visible type map.
     *
     * @param declaration declaration node containing variable declarators
     * @param source full source text
     * @param visibleTypes in-scope variable-to-type map
     */
    private void collectDeclaredVariables(TSNode declaration, String source, Map<String, String> visibleTypes) {
        TSNode typeNode = getTypeNode(declaration);
        if (typeNode == null) {
            return;
        }

        String declaredType = getNodeText(typeNode, source);
        for (int i = 0; i < declaration.getChildCount(); i++) {
            TSNode child = declaration.getChild(i);
            if (!"variable_declarator".equals(child.getType())) {
                continue;
            }

            TSNode nameNode = getIdentifierNode(child, "name");
            if (nameNode != null) {
                visibleTypes.put(getNodeText(nameNode, source), declaredType);
            }
        }
    }

    /**
     * Adds method parameters to the visible type map.
     *
     * @param methodDeclaration method declaration node
     * @param source full source text
     * @param visibleTypes in-scope variable-to-type map
     */
    private void collectMethodParameters(TSNode methodDeclaration, String source, Map<String, String> visibleTypes) {
        TSNode parametersNode = methodDeclaration.getChildByFieldName("parameters");
        if (parametersNode == null) {
            return;
        }

        for (int i = 0; i < parametersNode.getChildCount(); i++) {
            TSNode parameterNode = parametersNode.getChild(i);
            if (!"formal_parameter".equals(parameterNode.getType())) {
                continue;
            }

            TSNode typeNode = getTypeNode(parameterNode);
            TSNode nameNode = getIdentifierNode(parameterNode, "name");
            if (typeNode != null && nameNode != null) {
                visibleTypes.put(getNodeText(nameNode, source), getNodeText(typeNode, source));
            }
        }
    }

    /**
     * Resolves the receiver type for a method invocation object.
     *
     * @param objectNode invocation receiver node
     * @param source full source text
     * @param visibleTypes in-scope variable-to-type map
     * @return resolved receiver type or {@code null} when unresolved
     */
    private String resolveReceiverType(TSNode objectNode, String source, Map<String, String> visibleTypes) {
        if (objectNode == null) {
            return null;
        }

        if ("identifier".equals(objectNode.getType())) {
            return visibleTypes.get(getNodeText(objectNode, source));
        }

        if ("field_access".equals(objectNode.getType())) {
            TSNode fieldNode = objectNode.getChildByFieldName("field");
            if (fieldNode != null) {
                return visibleTypes.get(getNodeText(fieldNode, source));
            }
        }

        String objectText = getNodeText(objectNode, source);
        if (!objectText.isEmpty() && Character.isUpperCase(objectText.charAt(0))) {
            return objectText;
        }

        return null;
    }

    /**
     * Provides a simple fallback from instance-style receiver names to class-style receiver names.
     * Example: {@code service.add} -> {@code Service.add} when {@code Service.add} is known.
     *
     * @param objectText invocation receiver text
     * @param methodName invocation method name
     * @return class-qualified call id when known, otherwise null
     */
    private String resolveKnownMethodFromInstanceName(String objectText, String methodName) {
        if (objectText == null || objectText.isBlank() || methodName == null || methodName.isBlank()) {
            return null;
        }

        if (!Character.isLowerCase(objectText.charAt(0))) {
            return null;
        }

        String inferredClass = Character.toUpperCase(objectText.charAt(0)) + objectText.substring(1);
        String inferredMethodId = inferredClass + "." + methodName;
        if (knownMethodIds.contains(inferredMethodId)) {
            return inferredMethodId;
        }
        return null;
    }

    /**
     * Traverses the syntax tree recursively and records classes, methods, and invocations.
     *
     * @param node current tree node being visited
     * @param path source file path for logging context
     * @param source source text used to resolve node text
     * @param currentMethodId currently active method id during traversal
     * @param currentClass currently active class name during traversal
     * @param visibleTypes in-scope variable-to-type map (method-level scope)
     */
    private void walkTree(TSNode node, Path path, String source, String currentMethodId, String currentClass, Map<String, String> visibleTypes) {
        Map<String, String> currentScope = visibleTypes;
        String type = node.getType();

        if ("class_declaration".equals(type) || "method_declaration".equals(type) || "block".equals(type)) {
            currentScope = new HashMap<>(visibleTypes);
        }

        if ("class_declaration".equals(type)) {
            TSNode nodeName = node.getChildByFieldName("name");
            if (nodeName != null) {
                currentClass = getNodeText(nodeName, source);
                this.graphInMemory.addClassNode(currentClass);
                System.out.println("Found class: " + currentClass + " in file: " + path);
            }
        }

        if ("method_declaration".equals(type)) {
            TSNode nodeName = node.getChildByFieldName("name");
            if (nodeName != null) {
                String methodName = getNodeText(nodeName, source);
                currentMethodId = this.graphInMemory.addMethodNode(methodName, currentClass);
                knownMethodIds.add(currentMethodId);
                collectMethodParameters(node, source, currentScope);
                System.out.println("Found method: " + methodName + " in class: " + currentClass);
            }
        }

        if ("field_declaration".equals(type) || "local_variable_declaration".equals(type)) {
            collectDeclaredVariables(node, source, currentScope);
        }

        if ("method_invocation".equals(type)) {
            TSNode nodeObject = node.getChildByFieldName("object");
            TSNode nodeName = node.getChildByFieldName("name");
            if (nodeName != null && nodeObject != null && currentMethodId != null) {
                String objectText = getNodeText(nodeObject, source);
                String methodName = getNodeText(nodeName, source);
                String receiverType = resolveReceiverType(nodeObject, source, currentScope);
                String invocation = receiverType != null ? receiverType + "." + methodName : objectText + "." + methodName;

                if (receiverType == null) {
                    String resolvedKnownMethod = resolveKnownMethodFromInstanceName(objectText, methodName);
                    if (resolvedKnownMethod != null) {
                        invocation = resolvedKnownMethod;
                    }
                }

                if (!objectText.startsWith("System.")) {
                    this.graphInMemory.addMethodCall(currentMethodId, invocation);
                    System.out.println("Found method invocation: " + invocation);
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            walkTree(node.getChild(i), path, source, currentMethodId, currentClass, currentScope);
        }

    }


    /**
     * Parses a single Java file and starts tree traversal from the root node.
     *
     * @param path Java source file path
     * @param parser configured tree-sitter Java parser
     */
    private void parseFile(Path path, TSParser parser) {
        try {
            String fileContents = Files.readString(path);
            TSTree tsTree = parser.parseString(null, fileContents);
            TSNode rootNode = tsTree.getRootNode();
            walkTree(rootNode, path, fileContents, null, null, new HashMap<>());
        } catch (IOException e) {
            System.err.println("Error reading file: " + path + " - " + e.getMessage());
        }
    }

    /**
     * Walks all Java files in the repository and parses each file.
     *
     * @param repoPath repository root path
     * @throws IOException if directory walking fails
     */
    public void parseRepository(Path repoPath) throws IOException {
        TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterJava());

        Files.walk(repoPath)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach((path) -> parseFile(path,parser));
    }

    /**
     * Finds the repository and triggers parsing/indexing when available.
     *
     * @param repoName repository folder name to index
     * @return optional repository path when indexing starts successfully
     * @throws IOException if repository lookup or traversal fails
     */
    public Optional<Path> startIndexing(String repoName) throws IOException {
        Optional<Path> repo = locateRepositoryInFileSystem(repoName);
        if (repo.isPresent()) {

            System.out.println("Starting indexing for: " + repo.get().toString());
            this.graphInMemory.resetGraph();
            this.knownMethodIds.clear();
            parseRepository(repo.get());
            this.graphInMemory.writeToJson();
            return repo;
        } else {
            System.out.println("Cannot start indexing; repository not found: " + repoName);
        }
        return Optional.empty();

    }
}
