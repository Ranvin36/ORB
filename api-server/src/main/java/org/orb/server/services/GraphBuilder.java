package org.orb.server.services;

import org.orb.server.models.GraphInMemory;
import org.orb.server.services.scanning.MetadataScanner;
import org.orb.server.services.scanning.TreeNodeUtils;
import org.treesitter.TSNode;

import java.util.*;

import static org.orb.server.services.scanning.TreeNodeUtils.*;
import static org.orb.server.services.scanning.TreeNodeUtils.getIdentifierNode;

public class GraphBuilder {
    private final MetadataScanner metadataScanner;

    public GraphBuilder(MetadataScanner metadataScanner) {
        this.metadataScanner = metadataScanner;
    }

    /**
     * Builds a readable fallback receiver name from raw AST text.
     */
    private String normalizeReceiverText(String rawText) {
        if (rawText == null) {
            return "";
        }
        String trimmed = rawText.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        // Keep identifier-like token when possible to avoid noisy fallback ids.
        int paren = trimmed.indexOf('(');
        if (paren > 0) {
            trimmed = trimmed.substring(0, paren);
        }
        return trimmed;
    }

    /**
     * Extracts declared variable names from a field or local variable declaration
     * and stores them in the current visible type map.
     *
     * @param declaration  field or local variable declaration node
     * @param sourceBytes  UTF-8 bytes of the source file
     * @param visibleTypes in-scope variable-to-type map to update
     */
    private void collectDeclaredVariables(TSNode declaration, byte[] sourceBytes, Map<String, String> visibleTypes) {
        TSNode typeNode = getTypeNode(declaration);
        if (!isValidNode(typeNode)) {
            return;
        }

        String declaredType = getNodeText(typeNode, sourceBytes);
        if (declaredType.isBlank()) {
            return;
        }

        for (int i = 0; i < declaration.getChildCount(); i++) {
            TSNode child = declaration.getChild(i);
            if (!isValidNode(child) || !"variable_declarator".equals(child.getType())) {
                continue;
            }

            TSNode nameNode = getIdentifierNode(child, "name");
            if (nameNode == null) {
                continue;
            }

            String variableName = getNodeText(nameNode, sourceBytes);
            if (!variableName.isBlank()) {
                visibleTypes.put(variableName, declaredType);
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

        String firstMethod = methodNames.getFirst();
        String firstCall = null;
//        Extract the type of the rootNode(the main object)
        String rootReceiverType = resolveReceiverType(rootObjectNode, sourceBytes, visibleTypes);
        if (rootReceiverType != null && !rootReceiverType.isBlank()) {
            firstCall = rootReceiverType + "." + firstMethod;
//        If no root object then use the current class
        } else if (rootObjectText.isBlank() && currentClass != null && !currentClass.isBlank()) {
            firstCall = currentClass + "." + firstMethod;
        } else {
            String fallbackRoot = normalizeReceiverText(rootObjectText);
            if (!fallbackRoot.isBlank()) {
                firstCall = fallbackRoot + "." + firstMethod;
            }
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
            String receiver = (nextReceiverType != null && !nextReceiverType.isBlank())
                    ? nextReceiverType
                    : previousMethod;
            String call = receiver + "." + methodName;

            resolvedCalls.add(call);
            nextReceiverType = metadataScanner.getKnownMethodReturnType(call);
            previousMethod = methodName;
        }

        return resolvedCalls;
    }


    public void buildGraph(TSNode node, byte[] sourceBytes, String currentMethodId, String currentClass, Map<String, String> visibleTypes, GraphInMemory graphInMemory) {

        if(!TreeNodeUtils.isValidNode(node)) {
            return;
        }
        Map<String, String> currentScope = visibleTypes == null ? new HashMap<>() : visibleTypes;

        String type = node.getType();

        if ("class_declaration".equals(type) || "method_declaration".equals(type) || "block".equals(type)) {
            currentScope = new HashMap<>(currentScope);
        }

        // ...existing code...
            if ("class_declaration".equals(type) || "enum_declaration".equals(type) || "record_declaration".equals(type)) {
                TSNode nodeName = node.getChildByFieldName("name");
                if (isValidNode(nodeName)) {
                    currentClass = getNodeText(nodeName, sourceBytes);
                    if (currentClass.isBlank()) {
                        currentClass = null;
                    }
                }
                // Pull in discovered field variables from pass 1 for class scope
                if (currentClass != null) {
                    Map<String, String> classFields = metadataScanner.getDiscoveredVariablesForScope(currentClass);
                    currentScope.putAll(classFields);
                }
            }

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
                        // Pull in discovered local variables from pass 1 for method scope
                        Map<String, String> localVars = metadataScanner.getDiscoveredVariablesForScope(currentMethodId);
                        currentScope.putAll(localVars);
                    }
                }
            }

            if ("field_declaration".equals(type) || "local_variable_declaration".equals(type)) {
                collectDeclaredVariables(node, sourceBytes, currentScope);
            }

            if ("method_invocation".equals(node.getType())) {
                TSNode nodeObject = node.getChildByFieldName("object");
                TSNode nodeMethod = node.getChildByFieldName("name");
                if (isValidNode(nodeMethod) && currentMethodId != null) {
                    String methodName = getNodeText(nodeMethod, sourceBytes);
                    if (!methodName.isBlank()) {
                        if (isValidNode(nodeObject) && "method_invocation".equals(nodeObject.getType())) {
                            List<String> chainCalls = splitNestedInvocations(node, sourceBytes, currentScope, currentClass);
                            for (String call : chainCalls) {
                                if (!call.isBlank() && !call.startsWith("System.")) {
                                    graphInMemory.addMethodCall(currentMethodId, call);
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
                                    if (receiverType != null && !receiverType.isBlank()) {
                                        invocation = receiverType + "." + methodName;
                                    } else {
                                        String fallbackReceiver = normalizeReceiverText(objectText);
                                        if (!fallbackReceiver.isBlank()) {
                                            invocation = fallbackReceiver + "." + methodName;
                                        }
                                    }
                                }
                            }

                            if (invocation != null && !invocation.isBlank()) {
                                graphInMemory.addMethodCall(currentMethodId, invocation);
                            }
                        }

                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            // Iteratively go through each node in the tree
            buildGraph(node.getChild(i), sourceBytes, currentMethodId, currentClass,currentScope, graphInMemory);
        }

    }
}
