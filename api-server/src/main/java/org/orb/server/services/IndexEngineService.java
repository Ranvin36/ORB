package org.orb.server.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.orb.server.models.GraphInMemory;
import org.springframework.stereotype.Service;
import org.treesitter.TSException;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJava;

@Service
public class IndexEngineService {
    private final GraphInMemory graphInMemory;
    private final Set<String> knownMethodIds;

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
     * Checks whether a node exists and is safe to read.
     * Verifies that the node is not null and can be accessed without throwing a TSException.
     *
     * @param node the tree-sitter node to validate
     * @return {@code true} if the node is non-null and accessible, {@code false} otherwise
     */
    private boolean isValidNode(TSNode node) {
        if (node == null) {
            return false;
        }
        try {
            node.getType();
            return true;
        } catch (TSException e) {
            return false;
        }
    }

    /**
     * Extracts source text for a tree-sitter node from UTF-8 byte offsets.
     *
     * @param node tree node whose text span should be read
     * @param sourceBytes UTF-8 bytes of the full source text that was parsed
     * @return source slice represented by the node, or empty string when unavailable
     */
    private String getNodeText(TSNode node, byte[] sourceBytes){
        if (!isValidNode(node) || sourceBytes == null) {
            return "";
        }
        try {
            int startingByte  = node.getStartByte();
            int endingByte = node.getEndByte();
            if (startingByte < 0 || endingByte < startingByte || endingByte > sourceBytes.length) {
                return "";
            }
            return new String(sourceBytes, startingByte, endingByte - startingByte, StandardCharsets.UTF_8);
        } catch (TSException e) {
            return "";
        }
    }

    /**
     * Tries to resolve a declaration type node across common Java grammar variants.
     *
     * @param declaration declaration node
     * @return type node when found, otherwise null
     */
    private TSNode getTypeNode(TSNode declaration) {
        TSNode typeNode = declaration.getChildByFieldName("type");
        if (isValidNode(typeNode)) {
            return typeNode;
        }

        for (int i = 0; i < declaration.getChildCount(); i++) {
            TSNode child = declaration.getChild(i);
            if (!isValidNode(child)) {
                continue;
            }
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
     * It first checks the specified field, then scans all children for an {@code identifier} node.
     *
     * @param node node containing an identifier
     * @param fieldName preferred field name
     * @return identifier node when found, otherwise null
     */
    private TSNode getIdentifierNode(TSNode node, String fieldName) {
//      Extract name based on the fieldName
        TSNode idNode = node.getChildByFieldName(fieldName);
        if (isValidNode(idNode)) {
            return idNode;
        }
//      Go through every child and return the identifier node
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (!isValidNode(child)) {
                continue;
            }
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
     * @param sourceBytes UTF-8 bytes of full source text
     * @param visibleTypes in-scope variable-to-type map
     */
    private void collectDeclaredVariables(TSNode declaration, byte[] sourceBytes, Map<String, String> visibleTypes) {
//        Extract type node
        TSNode typeNode = getTypeNode(declaration);
        if (typeNode == null) {
            return;
        }
//        Extract type as a text
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
//          Extract the identifier node
            TSNode nameNode = getIdentifierNode(child, "name");
            if (nameNode != null) {
//                Add the node type & name as key-value pair
                String variableName = getNodeText(nameNode, sourceBytes);
                if (!variableName.isBlank()) {
                    visibleTypes.put(variableName, declaredType);
                }
            }
        }
    }

    /**
     * Adds method parameters to the visible type map.
     *
     * @param methodDeclaration method declaration node
     * @param sourceBytes UTF-8 bytes of full source text
     * @param visibleTypes in-scope variable-to-type map
     */
    private void collectMethodParameters(TSNode methodDeclaration, byte[] sourceBytes, Map<String, String> visibleTypes) {
//        Get & check if parameters exists in the method
        TSNode parametersNode = methodDeclaration.getChildByFieldName("parameters");
        if (!isValidNode(parametersNode)) {
            return;
        }

//        Go through each parameter and add it to the visible types map, using the parameter name as key and parameter type as value
        for (int i = 0; i < parametersNode.getChildCount(); i++) {
            TSNode parameterNode = parametersNode.getChild(i);
            if (!isValidNode(parameterNode)) {
                continue;
            }
//            Only process formal parameters (e.g., 'int a', 'String b'); skip punctuation and other non-formal nodes
            if (!"formal_parameter".equals(parameterNode.getType())) {
                continue;
            }
//          Extract node type -> int
            TSNode typeNode = getTypeNode(parameterNode);
//          Extract node name -> a,b
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
     *
     * @param objectNode invocation receiver node
     * @param sourceBytes UTF-8 bytes of full source text
     * @param visibleTypes in-scope variable-to-type map
     * @return resolved receiver type or {@code null} when unresolved
     */
    private String resolveReceiverType(TSNode objectNode, byte[] sourceBytes, Map<String, String> visibleTypes) {
        if (!isValidNode(objectNode)) {
            return null;
        }
//      Check if the receiver node type exists in visibleTypes -> a single name token(no dot)
//      Ex :- service, Calculator, add, count
        if ("identifier".equals(objectNode.getType())) {
            return visibleTypes.get(getNodeText(objectNode, sourceBytes));
        }
//      If the node type is field_access(this.service.add), extract the field name and check if it exists in visibleTypes as well
//      A dot-member expression (something.name)
//      Ex :- this.service, obj.value, config.database
        if ("field_access".equals(objectNode.getType())) {
            TSNode fieldNode = objectNode.getChildByFieldName("field");
            if (isValidNode(fieldNode)) {
                return visibleTypes.get(getNodeText(fieldNode, sourceBytes));
            }
        }
//      Fallback -> If the name starts capital, consider it as a class
        String objectText = getNodeText(objectNode, sourceBytes);
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
//      Construct class & method name to use as method id and check if it exists in the known method ids set. This is a simple heuristic that can help in cases where the receiver type cannot be resolved but the instance name follows common Java naming conventions.
        String inferredClass = Character.toUpperCase(objectText.charAt(0)) + objectText.substring(1);
        String inferredMethodId = inferredClass + "." + methodName;
        if (knownMethodIds.contains(inferredMethodId)) {
            return inferredMethodId;
        }
        return null;
    }

    private List<String> collectExtendedTypes(TSNode declarationNode, byte[] sourceBytes) {
        List<String> extendedTypes = new ArrayList<>();
        TSNode extendedNodes = declarationNode.getChildByFieldName("superclass");
        if(!isValidNode(extendedNodes)){
            extendedNodes = declarationNode.getChildByFieldName("superclasses");
        }
        if(!isValidNode(extendedNodes)){
            return extendedTypes;
        }
        collectTypeIdentifiers(extendedNodes, sourceBytes,extendedTypes);
        return extendedTypes;
    }

    /**
     * Collects interface names from class-like declarations.
     */
    private List<String> collectImplementedTypes(TSNode declarationNode, byte[] sourceBytes) {
        List<String> implementedTypes = new ArrayList<>();
//        Extract the interface nodes
        TSNode interfacesNode = declarationNode.getChildByFieldName("interfaces");
        if (!isValidNode(interfacesNode)) {
            interfacesNode = declarationNode.getChildByFieldName("interface");
        }
        if (!isValidNode(interfacesNode)) {
            return implementedTypes;
        }

        collectTypeIdentifiers(interfacesNode, sourceBytes, implementedTypes);
        return implementedTypes;
    }

    /**
     * Recursively extracts type-like identifiers from a node.
     */
    private void collectTypeIdentifiers(TSNode node, byte[] sourceBytes, List<String> output) {
        if (!isValidNode(node)) {
            return;
        }
//        Extract Node Type
        String nodeType = node.getType();
//        A simple type name with no package/class prefix -> Service, Runnable, String
        if ("type_identifier".equals(nodeType)
//        A qualified type name with dots (.), usually package or outer-class scope -> Service.Add(), com.acme.Plugin
                || "scoped_type_identifier".equals(nodeType)
//        A type with type arguments ->  List<String>, Map<String, Integer>, MyType<Foo>
                || "generic_type".equals(nodeType)) {
            String typeName = getNodeText(node, sourceBytes);
            if (!typeName.isBlank()) {
                output.add(typeName);
            }
            return;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectTypeIdentifiers(node.getChild(i), sourceBytes, output);
        }
    }

    /**
     * Traverses the syntax tree recursively and records classes, methods, and invocations.
     *
     * @param node current tree node being visited
     * @param path source file path for logging context
     * @param source source text used for logging/debugging
     * @param sourceBytes UTF-8 bytes used to resolve node text from tree-sitter byte spans
     * @param currentMethodId currently active method id during traversal
     * @param currentClass currently active class name during traversal
     * @param visibleTypes in-scope variable-to-type map (nested lexical scope: new copies created for class/method/block contexts)
     */
    private void walkTree(TSNode node, Path path, String source, byte[] sourceBytes, String currentMethodId, String currentClass, Map<String, String> visibleTypes) {
        if (!isValidNode(node)) {
            return;
        }
        Map<String, String> currentScope = visibleTypes;
        String type = node.getType();

        if ("class_declaration".equals(type) || "method_declaration".equals(type) || "block".equals(type)) {
            currentScope = new HashMap<>(visibleTypes);
        }
//        If a class is declared in the current node, extract its name and add it to the graph. Update the current class context for nested nodes.
        if ("class_declaration".equals(type) || "enum_declaration".equals(type) || "record_declaration".equals(type)) {
            TSNode nodeName = node.getChildByFieldName("name");
            if (isValidNode(nodeName)) {
                currentClass = getNodeText(nodeName, sourceBytes);
                if (currentClass.isBlank()) {
                    currentClass = null;
                }

//                Add the node to the graph
                if (currentClass != null) {
                    List<String> implementedTypes = collectImplementedTypes(node, sourceBytes);
                    List<String> extendedTypes = collectExtendedTypes(node,sourceBytes);
                    this.graphInMemory.addClassNode(currentClass, type, implementedTypes, extendedTypes);
                    System.out.println("Found class: " + currentClass + " in file: " + path);
                }
            }
        }

//        If a method is declared in the current node, extract its name and add it to the graph. Update the current method context for nested nodes. Also collect method parameters into the visible type map.
        if ("method_declaration".equals(type)) {
            TSNode nodeName = node.getChildByFieldName("name");
            if (isValidNode(nodeName) && currentClass != null) {
                String methodName = getNodeText(nodeName, sourceBytes);
                if (methodName.isBlank()) {
                    methodName = null;
                }
                if (methodName != null) {
                    currentMethodId = this.graphInMemory.addMethodNode(methodName, currentClass);
                    knownMethodIds.add(currentMethodId);
//                Add method parameters to visibleTypes(some elements can be declared as parameters)
                    collectMethodParameters(node, sourceBytes, currentScope);
                    System.out.println("Found method: " + methodName + " in class: " + currentClass);
                }
            }
        }

        if ("field_declaration".equals(type) || "local_variable_declaration".equals(type)) {
//            Register all declared variables and their types in the current scope
            collectDeclaredVariables(node, sourceBytes, currentScope);
        }

        if ("method_invocation".equals(type)) {
//           Extract the method invocation receiver and method name (e.g., service.add() or Service.add())
            TSNode nodeObject = node.getChildByFieldName("object");
            TSNode nodeName = node.getChildByFieldName("name");
            if (isValidNode(nodeName) && isValidNode(nodeObject) && currentMethodId != null) {
                String objectText = getNodeText(nodeObject, sourceBytes);
                String methodName = getNodeText(nodeName, sourceBytes);
                if (!objectText.isBlank() && !methodName.isBlank()) {
//                Add type resolution to extract the element type
                    String receiverType = resolveReceiverType(nodeObject, sourceBytes, currentScope);
                    String invocation = receiverType != null ? receiverType + "." + methodName : objectText + "." + methodName;

                    if (receiverType == null) {
                        //Simple fallback for type resolution
                        String resolvedKnownMethod = resolveKnownMethodFromInstanceName(objectText, methodName);
                        if (resolvedKnownMethod != null) {
                            invocation = resolvedKnownMethod;
                        }
                    }

                    if (!objectText.startsWith("System.")) {
//                    Add method invocation into the graph
                        this.graphInMemory.addMethodCall(currentMethodId, invocation);
                        System.out.println("Found method invocation: " + invocation);
                    }
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
//            Iteratively go through each node in the tree
            walkTree(node.getChild(i), path, source, sourceBytes, currentMethodId, currentClass, currentScope);
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
//            Read the content in the file
            String fileContents = Files.readString(path);
            byte[] fileBytes = fileContents.getBytes(StandardCharsets.UTF_8);
//            Parse the file to a tree
            TSTree tsTree = parser.parseString(null, fileContents);
            TSNode rootNode = tsTree.getRootNode();
//            Go through the tree to extract symbols
            walkTree(rootNode, path, fileContents, fileBytes, null, null, new HashMap<>());
        } catch (IOException | TSException e) {
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
//        Parse the files in the repository one by one
        try (var stream = Files.walk(repoPath)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach((path) -> parseFile(path, parser));
        }
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
//            Clear the graph before indexing to ensure no previous nodes exist
            this.graphInMemory.resetGraph();
            this.knownMethodIds.clear();
//            Start indexing the repository
            parseRepository(repo.get());
            this.graphInMemory.writeToJson();
            return repo;
        } else {
            System.out.println("Cannot start indexing; repository not found: " + repoName);
        }
        return Optional.empty();

    }
}
