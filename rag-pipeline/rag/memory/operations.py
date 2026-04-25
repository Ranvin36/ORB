from uuid import uuid4

from .chroma_client import collection


def store_memory(user_id: str, text: str, embedding: list[float]) -> str:
    memory_id = f"{user_id}_{uuid4()}"
    collection.add(
        ids=[memory_id],
        documents=[text],
        embeddings=[embedding],
        metadatas=[{"user_id": user_id}]
    )
    return memory_id


def get_memory(user_id: str, query_embedding: list[float]) -> list[str]:
    results = collection.query(
        query_embeddings=[query_embedding],
        n_results=5,
        where={"user_id": user_id}
    )

    documents = results.get("documents", [])
    if not documents:
        return []
    return [doc for doc in documents[0] if doc is not None]