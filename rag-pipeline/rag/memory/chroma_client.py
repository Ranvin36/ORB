from pathlib import Path

import chromadb

# Keep the persisted DB location stable regardless of process working directory.
DB_PATH = str(Path(__file__).resolve().parent / "chroma_db")

client = chromadb.PersistentClient(path=DB_PATH)

collection = client.get_or_create_collection(
    name="memory"
)
