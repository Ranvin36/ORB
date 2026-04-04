package org.orb.server.services.scanning;

import java.nio.charset.StandardCharsets;

import org.treesitter.TSException;
import org.treesitter.TSNode;

public final class TreeNodeUtils {
    private TreeNodeUtils() {
    }

    /**
     * Checks whether a tree-sitter node exists and can be read safely.
     *
     * @param node tree-sitter node to validate
     * @return {@code true} when the node is usable, otherwise {@code false}
     */
    public static boolean isValidNode(TSNode node) {
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
     * Reads the source text covered by a tree-sitter node.
     *
     * @param node        tree node whose text span should be read
     * @param sourceBytes UTF-8 bytes of the source file
     * @return node text, or an empty string when the node cannot be read safely
     */
    public static String getNodeText(TSNode node, byte[] sourceBytes) {
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
     * Resolves the type node from a declaration by checking the "type" field
     * first, then scanning children for common type node patterns.
     *
     * @param declaration declaration node (for example field/local/formal parameter)
     * @return type node when found, otherwise null
     */
    public static TSNode getTypeNode(TSNode declaration) {
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
     * Resolves an identifier child node by checking a preferred field first,
     * then scanning children for an {@code identifier} node.
     *
     * @param node      parent node containing an identifier
     * @param fieldName preferred field name to check first
     * @return identifier node when found, otherwise null
     */
    public static TSNode getIdentifierNode(TSNode node, String fieldName) {
        TSNode idNode = node.getChildByFieldName(fieldName);
        if (isValidNode(idNode)) {
            return idNode;
        }

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
}

