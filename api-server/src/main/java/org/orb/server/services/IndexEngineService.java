package org.orb.server.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.orb.server.services.scanning.TreeNodeUtils.getIdentifierNode;
import static org.orb.server.services.scanning.TreeNodeUtils.getNodeText;
import static org.orb.server.services.scanning.TreeNodeUtils.getTypeNode;
import static org.orb.server.services.scanning.TreeNodeUtils.isValidNode;

import org.orb.server.models.GraphInMemory;
import org.orb.server.services.scanning.MetadataScanner;
import org.springframework.stereotype.Service;
import org.treesitter.TSException;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJava;

@Service
public class IndexEngineService {
    private final GraphInMemory graphInMemory;
    private final MetadataScanner metadataScanner;

    /**
     * Creates a new indexing service with an empty in-memory graph.
     */
    public IndexEngineService() {
        this.graphInMemory = new GraphInMemory();
        this.metadataScanner = new MetadataScanner();
    }

    /**
     * Locates the repository directory under the user's Documents folder.
     * Returns an Optional containing the Path if the directory exists and contains
     * at least one entry.
     * Returns Optional.empty() if not found or on invalid input.
     *
     * @param repositoryName repository folder name to search under Documents/orb
     * @return optional path to the repository when found and non-empty
     * @throws IOException if directory access fails while checking repository
     *                     contents
     */
    public Optional<Path> locateRepositoryInFileSystem(String repositoryName) throws IOException {
        if (repositoryName == null || repositoryName.isBlank()) {
            return Optional.empty();
        }

        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return Optional.empty();
        }

        Path repoPath = Path.of(userHome, "Documents", "orb", repositoryName);

        if (Files.exists(repoPath) && Files.isDirectory(repoPath)) {
            // Use DirectoryStream to avoid leaving an open stream
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(repoPath)) {
                if (ds.iterator().hasNext()) {
                    return Optional.of(repoPath);
                } else {
                    // directory exists but is empty -> treat as not found (matches original
                    // behavior)
                    return Optional.empty();
                }
            }
        }

        return Optional.empty();
    }


    /**
     * Extracts and registers all declared variables from a field or local variable
     * declaration into the visible types map.
     * For each variable declarator, resolves the declared type and adds the mapping
     * {@code variableName -> declaredType} to the scope.
     *
     * @param declaration  field or local variable declaration node
     * @param sourceBytes  UTF-8 bytes of full source text
     * @param visibleTypes in-scope variable-to-type map to update
     */
    private void collectDeclaredVariables(TSNode declaration, byte[] sourceBytes, Map<String, String> visibleTypes) {
        // Extract type node
        TSNode typeNode = getTypeNode(declaration);
        if (typeNode == null) {
            return;
        }
        // Extract type as a text
        String declaredType = getNodeText(typeNode, sourceBytes);
        if (declaredType.isBlank()) {
            return;
        }
        for (int i = 0; i < declaration.getChildCount(); i++) {
            TSNode child = declaration.getChild(i);
            if (!isValidNode(child)) {
                continue;
            }
            if (!"variable_declarator".equals(child.getType())) {
                continue;
            }
            // Extract the identifier node
            TSNode nameNode = getIdentifierNode(child, "name");
            if (nameNode != null) {
                // Add the node type & name as key-value pair
                String variableName = getNodeText(nameNode, sourceBytes);
                if (!variableName.isBlank()) {
                    visibleTypes.put(variableName, declaredType);
                }
            }
        }
    }

    /**
     * Extracts and registers method parameters into the visible types map.
     * For each formal parameter node, resolves the parameter type and name,
     * then adds the mapping {@code parameterName -> parameterType} to the scope.
     * Skips non-formal parameters (e.g., punctuation, modifiers).
     *
     * @param methodDeclaration method declaration node containing parameters
     * @param sourceBytes       UTF-8 bytes of full source text
     * @param visibleTypes      in-scope variable-to-type map to update
     */
    private void collectMethodParameters(TSNode methodDeclaration, byte[] sourceBytes,
            Map<String, String> visibleTypes) {
        // Get & check if parameters exists in the method
        TSNode parametersNode = methodDeclaration.getChildByFieldName("parameters");
        if (!isValidNode(parametersNode)) {
            return;
        }

        // Go through each parameter and add it to the visible types map, using the
        // parameter name as key and parameter type as value
        for (int i = 0; i < parametersNode.getChildCount(); i++) {
            TSNode parameterNode = parametersNode.getChild(i);
            if (!isValidNode(parameterNode)) {
                continue;
            }
            // Only process formal parameters (e.g., 'int a', 'String b'); skip punctuation
            // and other non-formal nodes
            if (!"formal_parameter".equals(parameterNode.getType())) {
                continue;
            }
            // Extract node type -> int
            TSNode typeNode = getTypeNode(parameterNode);
            // Extract node name -> a,b
            TSNode nameNode = getIdentifierNode(parameterNode, "name");
            if (typeNode != null && nameNode != null) {
                String parameterName = getNodeText(nameNode, sourceBytes);
                String parameterType = getNodeText(typeNode, sourceBytes);
                if (!parameterName.isBlank() && !parameterType.isBlank()) {
                    visibleTypes.put(parameterName, parameterType);
                }
            }
        }
    }

    /**
     * Resolves the receiver type for a method invocation object.
     * Attempts three resolution strategies in order:
     * 1. If receiver is a plain identifier, look it up in visible types
     * 2. If receiver is a field_access (dot expression), extract field and look up
     * 3. If receiver text starts with uppercase, treat as a class name literal
     *
     * @param objectNode   invocation receiver node
     * @param sourceBytes  UTF-8 bytes of full source text
     * @param visibleTypes in-scope variable-to-type map
     * @return resolved receiver type (e.g., "Service", "Calculator") or {@code null} when unresolved
     */
    private String resolveReceiverType(TSNode objectNode, byte[] sourceBytes, Map<String, String> visibleTypes) {
        if (!isValidNode(objectNode)) {
            return null;
        }
        // Check if the receiver node type exists in visibleTypes -> a single name
        // token(no dot)
        // Ex :- service, Calculator, add, count
        if ("identifier".equals(objectNode.getType())) {
            return visibleTypes.get(getNodeText(objectNode, sourceBytes));
        }
        // If the node type is field_access(this.service.add), extract the field name
        // and check if it exists in visibleTypes as well
        // A dot-member expression (something.name)
        // Ex :- this.service, obj.value, config.database
        if ("field_access".equals(objectNode.getType())) {
            TSNode fieldNode = objectNode.getChildByFieldName("field");
            if (isValidNode(fieldNode)) {
                return visibleTypes.get(getNodeText(fieldNode, sourceBytes));
            }
        }
        // Fallback -> If the name starts capital, consider it as a class
        String objectText = getNodeText(objectNode, sourceBytes);
        if (!objectText.isEmpty() && Character.isUpperCase(objectText.charAt(0))) {
            return objectText;
        }

        return null;
    }

    /**
     * Provides a simple fallback from instance-style receiver names to class-style
     * receiver names.
     * Example: {@code service.add} -> {@code Service.add} when {@code Service.add}
     * is known.
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
        // Construct class & method name to use as method id and check if it exists in
        // the known method ids set. This is a simple heuristic that can help in cases
        // where the receiver type cannot be resolved but the instance name follows
        // common Java naming conventions.
        String inferredClass = Character.toUpperCase(objectText.charAt(0)) + objectText.substring(1);
        String inferredMethodId = inferredClass + "." + methodName;
        if (metadataScanner.hasKnownMethodId(inferredMethodId)) {
            return inferredMethodId;
        }
        return null;
    }


    /**
     * Compares two nodes by source byte range and node type.
     *
     * @param left  first node to compare
     * @param right second node to compare
     * @return {@code true} when both nodes point to the same span and type,
     *         otherwise {@code false}
     */
    private boolean sameNode(TSNode left, TSNode right) {
        if (!isValidNode(left) || !isValidNode(right)) {
            return false;
        }
        try {
            return left.getStartByte() == right.getStartByte()
                    && left.getEndByte() == right.getEndByte()
                    && left.getType().equals(right.getType());
        } catch (TSException e) {
            return false;
        }
    }

    /**
     * Checks whether a method invocation node is used only as the object part of
     * another invocation.
     * Example: in {@code a.b().c()}, the node for {@code a.b()} is nested object
     * of the parent invocation for {@code c()}.
     *
     * @param invocationNode invocation node to inspect
     * @return {@code true} when the node is the parent invocation's object field,
     *         otherwise {@code false}
     */
    private boolean isNestedInvocationObject(TSNode invocationNode) {
        if (!isValidNode(invocationNode)) {
            return false;
        }
        try {
            TSNode parent = invocationNode.getParent();
            if (!isValidNode(parent) || !"method_invocation".equals(parent.getType())) {
                return false;
            }
            TSNode parentObject = parent.getChildByFieldName("object");
            return sameNode(parentObject, invocationNode);
        } catch (TSException e) {
            return false;
        }
    }

    /**
     * Splits a chained invocation into separate calls and resolves receiver types
     * when possible.
     * Example: {@code order.getCustomer().getFullName()} becomes
     * {@code [Order.getCustomer, Customer.getFullName]} when return types are known.
     *
     * @param declarationNode root invocation node to split
     * @param sourceBytes     UTF-8 bytes of full source text
     * @param visibleTypes    in-scope variable-to-type map used for receiver lookup
     * @param currentClass    current class name used for local-call fallback
     * @return ordered list of resolved call ids for the chain; empty list when
     *         unresolved or invalid
     */
    private List<String> splitNestedInvocations(TSNode declarationNode, byte[] sourceBytes,
            Map<String, String> visibleTypes, String currentClass) {
        List<String> resolvedCalls = new ArrayList<>();

//        Check if it's a valid method invocation node before proceeding. If not, return empty list.
        if (!isValidNode(declarationNode) || !"method_invocation".equals(declarationNode.getType())) {
            return resolvedCalls;
        }

        List<String> methodNames = new ArrayList<>();
        TSNode currentNode = declarationNode;
        TSNode rootObjectNode = null;
        String rootObjectText = "";

//        Loop through the object nodes and add all the methods to the array
        while (isValidNode(currentNode) && "method_invocation".equals(currentNode.getType())) {
            TSNode methodNode = currentNode.getChildByFieldName("name");
            String methodName = getNodeText(methodNode, sourceBytes);
            if (methodName.isBlank()) {
                break;
            }
            methodNames.add(methodName);

            TSNode objectNode = currentNode.getChildByFieldName("object");
            if (!isValidNode(objectNode)) {
                break;
            }
            if (!"method_invocation".equals(objectNode.getType())) {
                rootObjectNode = objectNode;
                rootObjectText = getNodeText(objectNode, sourceBytes);
                break;
            }
            currentNode = objectNode;
        }

        if (methodNames.isEmpty()) {
            return resolvedCalls;
        }

        Collections.reverse(methodNames);

        String firstMethod = methodNames.get(0);
        String firstCall = null;
//        Extract the type of the rootNode(the main object)
        String rootReceiverType = resolveReceiverType(rootObjectNode, sourceBytes, visibleTypes);
        if (rootReceiverType != null && !rootReceiverType.isBlank()) {
            firstCall = rootReceiverType + "." + firstMethod;
        } else if (!rootObjectText.isBlank()) {
//            Manually update the className to uppercase as fallback
            String knownMethod = resolveKnownMethodFromInstanceName(rootObjectText, firstMethod);
            if (knownMethod != null) {
                firstCall = knownMethod;
            } else if (!rootObjectText.startsWith("System.")) {
                firstCall = rootObjectText + "." + firstMethod;
            }
//        If no root object then use the current class
        } else if (currentClass != null && !currentClass.isBlank()) {
            firstCall = currentClass + "." + firstMethod;
        }

        if (firstCall == null || firstCall.isBlank()) {
            return resolvedCalls;
        }
//        Add the initial call to the resolvedCalls
        resolvedCalls.add(firstCall);

        String nextReceiverType = metadataScanner.getKnownMethodReturnType(firstCall);
        String previousMethod = firstMethod;

//        Loop through the other methods and apply the resolution type
        for (int i = 1; i < methodNames.size(); i++) {
            String methodName = methodNames.get(i);
            String call = (nextReceiverType != null && !nextReceiverType.isBlank())
                    ? nextReceiverType + "." + methodName
                    : previousMethod + "." + methodName;

            resolvedCalls.add(call);
            nextReceiverType = metadataScanner.getKnownMethodReturnType(call);
            previousMethod = methodName;
        }

        return resolvedCalls;
    }

    /**
     * Traverses the syntax tree recursively and records classes, methods,
     * field/variable declarations, and method invocations into the in-memory graph.
     * Maintains nested lexical scopes (class/method/block) to resolve variable types
     * and method invocation receivers. Updates context (currentClass, currentMethodId)
     * as scope enters/exits.
     *
     * @param node            current tree node being visited
     * @param path            source file path for logging context
     * @param source          source text used for logging/debugging
     * @param sourceBytes     UTF-8 bytes used to resolve node text from tree-sitter
     *                        byte spans
     * @param currentMethodId currently active method id during traversal (null at class scope)
     * @param currentClass    currently active class name during traversal (null at file scope)
     * @param visibleTypes    in-scope variable-to-type map (nested lexical scope:
     *                        new copies created for class/method/block contexts)
     */
    private void walkTree(TSNode node, Path path, String source, byte[] sourceBytes, String currentMethodId,
            String currentClass, Map<String, String> visibleTypes) {
        if (!isValidNode(node)) {
            return;
        }
        Map<String, String> currentScope = visibleTypes;
        String type = node.getType();

        if ("class_declaration".equals(type) || "method_declaration".equals(type) || "block".equals(type)) {
            currentScope = new HashMap<>(visibleTypes);
        }
        // If a class is declared in the current node, extract its name and add it to
        // the graph. Update the current class context for nested nodes.
        if ("class_declaration".equals(type) || "enum_declaration".equals(type) || "record_declaration".equals(type)) {
            TSNode nodeName = node.getChildByFieldName("name");
            if (isValidNode(nodeName)) {
                currentClass = getNodeText(nodeName, sourceBytes);
                if (currentClass.isBlank()) {
                    currentClass = null;
                }
            }
        }

        // If a method is declared in the current node, extract its name and add it to
        // the graph. Update the current method context for nested nodes. Also collect
        // method parameters into the visible type map.
        if ("method_declaration".equals(type)) {
            TSNode nodeName = node.getChildByFieldName("name");
            if (isValidNode(nodeName) && currentClass != null) {
                String methodName = getNodeText(nodeName, sourceBytes);
                if (methodName.isBlank()) {
                    methodName = null;
                }
                if (methodName != null) {
                    currentMethodId = currentClass + "." + methodName;
                    // Add method parameters to visibleTypes(some elements can be declared as
                    // parameters)
                    collectMethodParameters(node, sourceBytes, currentScope);
                }
            }
        }

        if ("field_declaration".equals(type) || "local_variable_declaration".equals(type)) {
            // Register all declared variables and their types in the current scope
            collectDeclaredVariables(node, sourceBytes, currentScope);
        }

        if ("method_invocation".equals(type) && !isNestedInvocationObject(node)) {
            TSNode nodeObject = node.getChildByFieldName("object");
            TSNode nodeName = node.getChildByFieldName("name");
            if (isValidNode(nodeName) && currentMethodId != null) {
                String methodName = getNodeText(nodeName, sourceBytes);
                if (!methodName.isBlank()) {
                    if (isValidNode(nodeObject) && "method_invocation".equals(nodeObject.getType())) {
                        List<String> chainCalls = splitNestedInvocations(node, sourceBytes, currentScope, currentClass);
                        for (String call : chainCalls) {
                            if (!call.isBlank() && !call.startsWith("System.")) {
                                this.graphInMemory.addMethodCall(currentMethodId, call);
                            }
                        }
                    } else {
                        String invocation = null;

                        // Local call like add() has no receiver object, so bind it to currentClass.
                        if (!isValidNode(nodeObject)) {
                            if (currentClass != null && !currentClass.isBlank()) {
                                invocation = currentClass + "." + methodName;
                            }
                        } else {
                            String objectText = getNodeText(nodeObject, sourceBytes);
                            if (!objectText.isBlank() && !objectText.startsWith("System.")) {
                                String receiverType = resolveReceiverType(nodeObject, sourceBytes, currentScope);
                                invocation = receiverType != null ? receiverType + "." + methodName
                                        : objectText + "." + methodName;

                                if (receiverType == null) {
                                    String resolvedKnownMethod = resolveKnownMethodFromInstanceName(objectText, methodName);
                                    if (resolvedKnownMethod != null) {
                                        invocation = resolvedKnownMethod;
                                    }
                                }
                            }
                        }

                        if (invocation != null && !invocation.isBlank()) {
                            this.graphInMemory.addMethodCall(currentMethodId, invocation);
                        }
                    }
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            // Iteratively go through each node in the tree
            walkTree(node.getChild(i), path, source, sourceBytes, currentMethodId, currentClass, currentScope);
        }

    }

    /**
     * Parses a single Java file using tree-sitter and starts AST traversal from the root node.
     * Reads the file as UTF-8, parses it into a concrete syntax tree, and invokes walkTree
     * to extract classes, methods, and invocations.
     * Catches and logs any IOException (file read errors) or TSException (parse errors).
     *
     * @param path   Java source file path
     * @param parser configured tree-sitter Java parser
     */
    private void parseFile(Path path, TSParser parser, IndexingStage stage) {
        try {
            // Read the content in the file
            String fileContents = Files.readString(path);
            byte[] fileBytes = fileContents.getBytes(StandardCharsets.UTF_8);
            // Parse the file to a tree
            TSTree tsTree = parser.parseString(null, fileContents);
            TSNode rootNode = tsTree.getRootNode();
            if(stage == IndexingStage.SCANNING){
                this.metadataScanner.scanRepository(String.valueOf(path), rootNode, fileBytes, this.graphInMemory);
                return;
            }

            // Go through the tree to extract symbols
            walkTree(rootNode, path, fileContents, fileBytes, null, null, new HashMap<>());
        } catch (IOException | TSException e) {
            System.err.println("Error reading file: " + path + " - " + e.getMessage());
        }
    }

    /**
     * Recursively walks the repository directory tree and parses all .java files.
     * Creates a tree-sitter parser configured for Java, then uses Files.walk to
     * traverse the directory tree, filtering for regular .java files.
     * Parses each file individually via parseFile.
     *
     * @param repoPath repository root path to index
     * @throws IOException if directory walking fails or file I/O errors occur
     */
    public void parseRepository(Path repoPath) throws IOException {
        TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterJava());
        List<Path> javaFiles = new ArrayList<>();
        // Parse the files in the repository one by one
        try (var stream = Files.walk(repoPath)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(javaFiles::add);
        }
        for (Path javaFile : javaFiles) {
            parseFile(javaFile, parser, IndexingStage.SCANNING);
        }

        for (Path javaFile : javaFiles) {
            parseFile(javaFile, parser, IndexingStage.BUILDING);
        }

    }

    /**
     * Initiates the full indexing pipeline for a given repository name.
     * Locates the repository under Documents/orb, clears any previous graph state,
     * triggers parseRepository to extract all classes/methods/invocations,
     * then writes the final graph to JSON output.
     *
     * @param repoName repository folder name to index
     * @return optional repository path when indexing starts successfully; empty otherwise
     * @throws IOException if repository lookup or file I/O fails during indexing
     */
    public Optional<Path> startIndexing(String repoName) throws IOException {
        Optional<Path> repo = locateRepositoryInFileSystem(repoName);
        if (repo.isPresent()) {

            System.out.println("Starting indexing for: " + repo.get().toString());
            // Clear the graph before indexing to ensure no previous nodes exist
            this.graphInMemory.resetGraph();
            this.metadataScanner.reset();
            // Start indexing the repository
            parseRepository(repo.get());
            this.graphInMemory.writeToJson();
            return repo;
        } else {
            System.out.println("Cannot start indexing; repository not found: " + repoName);
        }
        return Optional.empty();

    }

}
