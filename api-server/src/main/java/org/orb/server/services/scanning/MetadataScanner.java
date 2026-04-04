package org.orb.server.services.scanning;

import org.orb.server.models.GraphInMemory;
import org.treesitter.TSNode;

import static org.orb.server.services.scanning.TreeNodeUtils.getNodeText;
import static org.orb.server.services.scanning.TreeNodeUtils.isValidNode;

import java.util.*;

/**
 * Scans a Java syntax tree and extracts per-file metadata such as package and
 * import declarations.
 */
public class MetadataScanner {
    /**
     * Stores scanned metadata keyed by file path when repository-level scanning
     * is orchestrated by callers.
     */
    protected Map<String, FileMetadata> fileMetadataMap;
    private final Map<String, String> knownMethodReturnTypes;
    protected Set<String> knownMethodIds;

    /**
     * Creates a scanner with an empty metadata map.
     */
    public MetadataScanner() {
        this.fileMetadataMap = new HashMap<>();
        this.knownMethodIds = new HashSet<>();
        this.knownMethodReturnTypes = new HashMap<>();
    }

    /** Clears all pass-1 scanner state before a new indexing run. */
    public void reset() {
        this.fileMetadataMap.clear();
        this.knownMethodIds.clear();
        this.knownMethodReturnTypes.clear();
    }

    /**
     * Checks whether a class-qualified method id is known from pass-1 declaration scan.
     *
     * @param methodId method id in the form {@code Class.method}
     * @return {@code true} when the method id exists in the scanned symbol set
     */
    public boolean hasKnownMethodId(String methodId) {
        return this.knownMethodIds.contains(methodId);
    }

    /**
     * Returns the declared return type for a previously scanned method id.
     *
     * @param methodId method id in the form {@code Class.method}
     * @return return type text when known; otherwise {@code null}
     */
    public String getKnownMethodReturnType(String methodId) {
        return this.knownMethodReturnTypes.get(methodId);
    }

    /**
     * Returns file-level metadata collected for a source file during pass 1.
     *
     * @param filePath absolute or canonical file path used as scanner key
     * @return metadata for that file, or {@code null} when not scanned
     */
    public FileMetadata getFileMetadata(String filePath) {
        return this.fileMetadataMap.get(filePath);
    }

    /**
     * Scans one parsed Java file for pass-1 artifacts.
     * Stores package/import metadata and registers class/method declarations
     * (including method return types) into shared in-memory indexes.
     *
     * @param filePath      file path key used to store {@link FileMetadata}
     * @param rootNode      root node of the parsed Java syntax tree
     * @param sourceBytes   UTF-8 bytes of the source file
     * @param graphInMemory graph store that receives class/method declaration nodes
     */
    public void scanRepository(String filePath, TSNode rootNode, byte[] sourceBytes, GraphInMemory graphInMemory) {
        FileMetadata pkgNImports = collectImportDeclarations(rootNode,sourceBytes);
        if (pkgNImports != null && filePath != null) {
            this.fileMetadataMap.put(filePath, pkgNImports);
        }
        scanTreeForDeclarations(rootNode, sourceBytes, null, graphInMemory);
    }


    /**
     * Collects package and import declarations from a parsed Java file.
     *
     * <p>This method records:</p>
     * <ul>
     *   <li>package name</li>
     *   <li>explicit type imports</li>
     *   <li>wildcard imports</li>
     *   <li>static member imports</li>
     *   <li>static wildcard imports</li>
     * </ul>
     *
     * @param rootNode    root node of the parsed Java source tree
     * @param sourceBytes UTF-8 bytes of the source file
     * @return populated {@link FileMetadata}, or {@code null} when the root node is invalid
     */
    public FileMetadata collectImportDeclarations(TSNode rootNode, byte[] sourceBytes) {
        if (!isValidNode(rootNode)) {
            return null;
        }

        FileMetadata fileMetadata = new FileMetadata();

//        Goes through each node and see if it's an import/package declaration
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            TSNode child = rootNode.getChild(i);
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

                }
            }
        }
        return fileMetadata;
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
     * Traverses the syntax tree and registers pass-1 declarations only.
     * This method intentionally captures class and method signatures, while
     * invocation/body-level relationship building is deferred to pass 2.
     *
     * @param node          current AST node
     * @param sourceBytes   UTF-8 bytes of the source file
     * @param currentClass  active class context while traversing nested nodes
     * @param graphInMemory graph store that receives class and method nodes
     */
    public void scanTreeForDeclarations(TSNode node, byte[] sourceBytes, String currentClass, GraphInMemory graphInMemory) {
        if(!isValidNode(node)) {
            return;
        }

        String type = node.getType();

        // If a class is declared in the current node, extract its name and add it to
        // the graph. Update the current class context for nested nodes.
        if ("class_declaration".equals(type) || "enum_declaration".equals(type) || "record_declaration".equals(type)) {
            TSNode nameNode = node.getChildByFieldName("name");
            String className = getNodeText(nameNode, sourceBytes);
            if (!className.isBlank()) {
                currentClass = className;
            }
            List<String> implementedTypes = null;
            List<String> extendedTypes =  null;
            if (currentClass != null) {
                implementedTypes = collectImplementedTypes(node, sourceBytes);
                extendedTypes = collectExtendedTypes(node, sourceBytes);
            }

            graphInMemory.addClassNode(currentClass, type, implementedTypes, extendedTypes);
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
                    String currentMethodId = graphInMemory.addMethodNode(methodName, currentClass);
                    knownMethodIds.add(currentMethodId);
                    String returnType = getMethodReturnType(node, sourceBytes);
                    if (returnType != null) {
                        knownMethodReturnTypes.put(currentMethodId, returnType);
                    }
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            scanTreeForDeclarations(node.getChild(i), sourceBytes, currentClass, graphInMemory);
        }
    }

}

