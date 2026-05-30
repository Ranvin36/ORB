from uuid import uuid4

from .chroma_client import collection


def store_memory(user_id: str, text: str, embedding: list[float]) -> str:
    memory_id = f"{user_id}_{uuid4()}"
    # embeddings temporarily disabled for Chroma — keep parameter for future use
    collection.add(
        ids=[memory_id],
        documents=[text],
        # embeddings=[embedding],
        metadatas=[{"user_id": user_id}]
    )
    return memory_id


def get_memory(user_id: str, query_embedding: list[float] | None) -> list[str]:
    # embeddings-based query is disabled for now. Original code (kept for reference):
    # results = collection.query(
    #     query_embeddings=[query_embedding],
    #     n_results=5,
    #     where={"user_id": user_id}
    # )
    # Instead, perform a simple retrieval of documents for the user.
    results = collection.get(where={"user_id": user_id}, include=["documents"], limit=5)

    documents = results.get("documents", [])
    if not documents:
        return []
    return [doc for doc in documents[0] if doc is not None]