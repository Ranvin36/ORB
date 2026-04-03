package org.orb.server.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.orb.server.models.GraphInMemory;
import org.orb.server.services.scanning.FileMetadata;
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
    private final Map<String, String> knownMethodReturnTypes;
    private final Map<String, FileMetadata>  fileMetadataMap;

    /**
     * Creates a new indexing service with an empty in-memory graph.
     */
    public IndexEngineService() {
        this.graphInMemory = new GraphInMemory();
        this.knownMethodIds = new HashSet<>();
        this.knownMethodReturnTypes = new HashMap<>();
        this.fileMetadataMap = new HashMap<>();
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
     * Checks whether a node exists and is safe to read.
     * Verifies that the node is not null and can be accessed without throwing a
     * TSException.
     *
     * @param node the tree-sitter node to validate
     * @return {@code true} if the node is non-null and accessible, {@code false}
     *         otherwise
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
     * @param node        tree node whose text span should be read
     * @param sourceBytes UTF-8 bytes of the full source text that was parsed
     * @return source slice represented by the node, or empty string when
     *         unavailable
     */
    private String getNodeText(TSNode node, byte[] sourceBytes) {
        if (!isValidNode(node) || sourceBytes == null) {
            return "";
        }
        try {
            int startingByte = node.getStartByte();
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
     * Resolves the type node from a declaration by checking the "type" field first,
     * then scanning children for common type node patterns.
     * Handles variations across Java grammar definitions (type_identifier, 
     * scoped_type_identifier, generic_type, and other *_type variants).
     *
     * @param declaration declaration node (e.g., field_declaration, local_variable_declaration)
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
     * Resolves an identifier child node by first checking the specified field name,
     * then scanning all children for an {@code identifier} node type.
     * Used to extract names from various declaration and expression nodes.
     *
     * @param node      parent node containing an identifier
     * @param fieldName preferred field name to check first (e.g., "name", "id")
     * @return identifier node when found, otherwise null
     */
    private TSNode getIdentifierNode(TSNode node, String fieldName) {
        // Extract name based on the fieldName
        TSNode idNode = node.getChildByFieldName(fieldName);
        if (isValidNode(idNode)) {
            return idNode;
        }
        // Go through every child and return the identifier node
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

    public FileMetadata collectImportDeclarations(TSNode rootNode, byte[] sourceBytes) {

        if(!isValidNode(rootNode)) {
            return null;
        }

        FileMetadata fileMetadata = new FileMetadata();

//        Goes through each node and see if it's an import/package declaration
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            TSNode child = rootNode.getChild(i);
            String type = child.getType();
//            Check if the node type is an import declaration
            if ("package_declaration".equals(child.getType())) {
//              Extract the name of the package
                String pkgName = getNodeText(child, sourceBytes)
                    .replace("package", "")
                    .replace(";","")
                    .trim();
//              Set package name file basis
                if (!pkgName.isBlank()) {
                    fileMetadata.setPackageName(pkgName);
                }

                continue;
            }

//          If an import is declared, extract and add it to the identified data structure
            if ("import_declaration".equals(child.getType())) {
//              Replace the redundant fields
                String importName = getNodeText(child, sourceBytes)
                        .replace(";","")
                        .trim();
//              If the import is of a static type then call the static Map/Set then call them accordingly
                boolean isStatic = importName.startsWith("import static ");
                importName = isStatic ? importName.substring("import static".length()).trim() :  importName.substring("import ".length()).trim();


                if (importName.endsWith(".*")) {
                    importName = importName.replace(".*", "").trim();
                    if (isStatic) {
                        fileMetadata.getStaticWildcardImports().add(importName);
                    }
                    else {
                        fileMetadata.getWildcardImports().add(importName);
                    }

                    continue;
                }
//              Add explicit calls(org.orb.server.ApiServerApplication) to the map considering static/non-static
                int lastDot = importName.lastIndexOf('.');
                if (lastDot > 0) {
                    if (isStatic) {
                        fileMetadata.getStaticMemberImports().put(importName.substring(lastDot+1), importName);
                    }
                    else {
                        fileMetadata.getExplicitTypeImports().put(importName.substring(lastDot+1), importName);
                    }

                    continue;
                }
//               If it's a local class then extract it
                if ("class_declaration".equals(type)
                        || "interface_declaration".equals(type)
                        || "enum_declaration".equals(type)
                        || "record_declaration".equals(type)) {
                    TSNode nameNode = child.getChildByFieldName("name");
                    String localType = getNodeText(nameNode, sourceBytes);
                    if (!localType.isBlank()) {
                        fileMetadata.getLocalTypeNames().add(localType);
                    }
                }
            }
        }
        return fileMetadata;
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
//                    System.out.println("parameterName: " + parameterName + ", parameterType: " + parameterType);

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
        if (knownMethodIds.contains(inferredMethodId)) {
            return inferredMethodId;
        }
        return null;
    }

    /**
     * Extracts and collects superclass names from a class-like declaration.
     * Checks both "superclass" and "superclasses" field names to handle grammar variations.
     * Uses {@code collectTypeIdentifiers} to recursively extract type names.
     *
     * @param declarationNode class, enum, or record declaration node
     * @param sourceBytes     UTF-8 bytes of full source text
     * @return list of superclass type names; empty list if none found
     */
    private List<String> collectExtendedTypes(TSNode declarationNode, byte[] sourceBytes) {
        List<String> extendedTypes = new ArrayList<>();
        TSNode extendedNodes = declarationNode.getChildByFieldName("superclass");
        if (!isValidNode(extendedNodes)) {
            extendedNodes = declarationNode.getChildByFieldName("superclasses");
        }
        if (!isValidNode(extendedNodes)) {
            return extendedTypes;
        }
        collectTypeIdentifiers(extendedNodes, sourceBytes, extendedTypes);
        return extendedTypes;
    }

    /**
     * Extracts and collects interface names from a class-like declaration.
     * Checks both "interfaces" and "interface" field names to handle grammar variations.
     * Uses {@code collectTypeIdentifiers} to recursively extract type names.
     *
     * @param declarationNode class or record declaration node
     * @param sourceBytes     UTF-8 bytes of full source text
     * @return list of interface type names; empty list if none found
     */
    private List<String> collectImplementedTypes(TSNode declarationNode, byte[] sourceBytes) {
        List<String> implementedTypes = new ArrayList<>();
        // Extract the interface nodes
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
     * Recursively extracts type-like identifiers from a node and its children.
     * Recognizes and collects three common type patterns:
     * 1. {@code type_identifier} - simple type names (e.g., Service, String)
     * 2. {@code scoped_type_identifier} - qualified names (e.g., com.acme.Plugin)
     * 3. {@code generic_type} - parameterized types (e.g., List&lt;String&gt;)
     *
     * @param node        the node to scan for type identifiers
     * @param sourceBytes UTF-8 bytes of full source text
     * @param output      list to accumulate discovered type names
     */
    private void collectTypeIdentifiers(TSNode node, byte[] sourceBytes, List<String> output) {
        if (!isValidNode(node)) {
            return;
        }
        // Extract Node Type
        String nodeType = node.getType();
        // A simple type name with no package/class prefix -> Service, Runnable, String
        if ("type_identifier".equals(nodeType)
                // A qualified type name with dots (.), usually package or outer-class scope ->
                // Service.Add(), com.acme.Plugin
                || "scoped_type_identifier".equals(nodeType)
                // A type with type arguments -> List<String>, Map<String, Integer>, MyType<Foo>
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
     * Reads a method's declared return type from its {@code type} field.
     * Returns {@code null} when the declaration has no readable type or when the
     * type is {@code void}.
     *
     * @param methodDeclaration method declaration node to inspect
     * @param sourceBytes       UTF-8 bytes of full source text
     * @return declared non-void return type, or {@code null} when unavailable
     */
    private String getMethodReturnType(TSNode methodDeclaration, byte[] sourceBytes) {
//        Extract the return type from method
        TSNode returnTypeNode = methodDeclaration.getChildByFieldName("type");
        if (!isValidNode(returnTypeNode)) {
            return null;
        }
        String returnType = getNodeText(returnTypeNode, sourceBytes);
        if (returnType.isBlank() || "void".equals(returnType)) {
            return null;
        }
        return returnType;
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

        String nextReceiverType = knownMethodReturnTypes.get(firstCall);
        String previousMethod = firstMethod;

//        Loop through the other methods and apply the resolution type
        for (int i = 1; i < methodNames.size(); i++) {
            String methodName = methodNames.get(i);
            String call = (nextReceiverType != null && !nextReceiverType.isBlank())
                    ? nextReceiverType + "." + methodName
                    : previousMethod + "." + methodName;

            resolvedCalls.add(call);
            nextReceiverType = knownMethodReturnTypes.get(call);
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

                // Add the node to the graph
                if (currentClass != null) {
                    List<String> implementedTypes = collectImplementedTypes(node, sourceBytes);
                    List<String> extendedTypes = collectExtendedTypes(node, sourceBytes);
                    this.graphInMemory.addClassNode(currentClass, type, implementedTypes, extendedTypes);
//                    System.out.println("Found class: " + currentClass + " in file: " + path);
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
                    currentMethodId = this.graphInMemory.addMethodNode(methodName, currentClass);
                    knownMethodIds.add(currentMethodId);
                    String returnType = getMethodReturnType(node, sourceBytes);
                    if (returnType != null) {
                        knownMethodReturnTypes.put(currentMethodId, returnType);
                    }
                    // Add method parameters to visibleTypes(some elements can be declared as
                    // parameters)
                    collectMethodParameters(node, sourceBytes, currentScope);
//                    System.out.println("Found method: " + methodName + " in class: " + currentClass);
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
                FileMetadata fileMetadata = collectImportDeclarations(rootNode,fileBytes);
                if(fileMetadata != null){
                    this.fileMetadataMap.put(String.valueOf(path), fileMetadata);
                }
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
            this.knownMethodIds.clear();
            this.knownMethodReturnTypes.clear();
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
