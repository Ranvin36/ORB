import os
from typing import Any, Optional

from neo4j import Driver, GraphDatabase
from neo4j.graph import Node, Path, Relationship

_driver: Optional[Driver] = None


def init_neo4j_driver() -> None:
    global _driver

    neo4j_uri = os.getenv("NEO4J_URI")
    neo4j_user = os.getenv("NEO4J_USERNAME")
    neo4j_password = os.getenv("NEO4J_PASSWORD")

    if neo4j_uri and neo4j_user and neo4j_password:
        _driver = GraphDatabase.driver(
            neo4j_uri,
            auth=(neo4j_user, neo4j_password),
        )


def close_neo4j_driver() -> None:
    global _driver

    if _driver:
        _driver.close()
        _driver = None


def get_neo4j_driver() -> Driver:
    if not _driver:
        raise RuntimeError(
            "Neo4j driver is not initialized. Call init_neo4j_driver() first."
        )
    return _driver


def neo4j_health_check() -> dict:
    if not _driver:
        return {
            "connected": False,
            "reason": "Set NEO4J_URI, NEO4J_USERNAME, and NEO4J_PASSWORD in .env",
        }

    with _driver.session() as session:
        result = session.run("RETURN 1 AS ok")
        record = result.single()

    return {"connected": bool(record and record["ok"] == 1)}


def _is_read_only_cypher(query: str) -> bool:
    normalized = " ".join(query.upper().split())
    write_keywords = (
        "CREATE",
        "MERGE",
        "DELETE",
        "DETACH DELETE",
        "SET",
        "REMOVE",
        "DROP",
        "LOAD CSV",
        "FOREACH",
    )
    return not any(keyword in normalized for keyword in write_keywords)


def run_query(
    query: str,
    params: Optional[dict[str, Any]] = None,
    read_only: bool = True,
) -> list[dict[str, Any]]:
    if not _driver:
        raise RuntimeError("Neo4j driver is not initialized.")

    if read_only and not _is_read_only_cypher(query):
        raise ValueError("Only read-only Cypher queries are allowed on this endpoint.")

    with _driver.session(default_access_mode="READ") as session:
        result = session.run(query, params or {})
        return [_serialize_value(record.data()) for record in result]


def _serialize_value(value: Any) -> Any:
    if isinstance(value, Node):
        return {
            "id": value.id,
            "labels": list(value.labels),
            "properties": dict(value),
        }

    if isinstance(value, Relationship):
        return {
            "id": value.id,
            "type": value.type,
            "start_node_id": value.start_node.id,
            "end_node_id": value.end_node.id,
            "properties": dict(value),
        }

    if isinstance(value, Path):
        return {
            "nodes": [_serialize_value(node) for node in value.nodes],
            "relationships": [_serialize_value(relationship) for relationship in value.relationships],
        }

    if isinstance(value, dict):
        return {key: _serialize_value(item) for key, item in value.items()}

    if isinstance(value, list):
        return [_serialize_value(item) for item in value]

    if isinstance(value, tuple):
        return [_serialize_value(item) for item in value]

    if hasattr(value, "isoformat"):
        try:
            return value.isoformat()
        except Exception:
            return str(value)

    return value