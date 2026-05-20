import os
from dotenv import load_dotenv
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from rag.graph import get_rag_service

from neo4j_client import (
    close_neo4j_driver,
    get_neo4j_driver,
    init_neo4j_driver,
    neo4j_health_check,
    run_query
)


app = FastAPI()
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
load_dotenv()


class Neo4jQueryRequest(BaseModel):
    cypher: str
    params: dict[str, Any] = Field(default_factory=dict)


class LlmQueryRequest(BaseModel):
    user_id: str | None = None
    message: str
    stream: bool = False
    batch_size: int = 70
    skip: int = 0


class MemoryStoreRequest(BaseModel):
    user_id: str
    text: str
    embedding: list[float]


class MemoryQueryRequest(BaseModel):
    user_id: str
    query_embedding: list[float]


def stream_talk_to_gpt_with_neo4j_tool(message: str):
    service = get_rag_service()
    answer = service.invoke(message)
    yield f"data: {answer}\n\n"
    yield "data: [DONE]\n\n"


def talk_to_gpt_with_neo4j_tool(message: str) -> str:
    service = get_rag_service()
    return service.invoke(message)


def stream_talk_to_gpt_with_memory(user_id: str, message: str):
    service = get_rag_service()
    answer = service.invoke_with_memory(user_id, message)
    yield f"data: {answer}\n\n"
    yield "data: [DONE]\n\n"


def talk_to_gpt_with_memory(user_id: str, message: str) -> str:
    service = get_rag_service()
    return service.invoke_with_memory(user_id, message)


@app.on_event("startup")
async def startup_event():
    init_neo4j_driver()


@app.on_event("shutdown")
async def shutdown_event():
    close_neo4j_driver()


@app.get("/neo4j/health")
async def neo4j_health():
    return neo4j_health_check()


@app.get("/neo4j/ping")
async def neo4j_ping():
    driver = get_neo4j_driver()
    with driver.session() as session:
        result = session.run("RETURN 'pong' AS message")
        record = result.single()
    return {"message": record["message"] if record else "no response"}


@app.post("/neo4j/query")
async def neo4j_query(payload: Neo4jQueryRequest):
    try:
        rows = run_query(payload.cypher, payload.params, read_only=True)
        return {"count": len(rows), "rows": rows}
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Query failed: {exc}") from exc


@app.post("/llm/stream")
async def llm_query(payload: LlmQueryRequest):
    try:
        if payload.stream:
            if payload.user_id:
                return StreamingResponse(
                    stream_talk_to_gpt_with_memory(payload.user_id, payload.message),
                    media_type="text/event-stream",
                    headers={
                        "Cache-Control": "no-cache",
                        "Connection": "keep-alive",
                        "X-Accel-Buffering": "no",
                    },
                )

            return StreamingResponse(
                stream_talk_to_gpt_with_neo4j_tool(payload.message),
                media_type="text/event-stream",
                headers={
                    "Cache-Control": "no-cache",
                    "Connection": "keep-alive",
                    "X-Accel-Buffering": "no",
                },
            )

        if payload.user_id:
            answer = talk_to_gpt_with_memory(payload.user_id, payload.message)
        else:
            answer = talk_to_gpt_with_neo4j_tool(payload.message)
        return {"answer": answer}
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"LLM query failed: {exc}") from exc

@app.get("/llm/summarize")
async def llm_summarize():
    try:
        unsummarized_nodes = run_query(
            """
            MATCH (n)
            WHERE n.summary IS NULL
            RETURN n
            ORDER BY id(n)
            LIMIT 70
            """,
            read_only=True,
        )
        return {
            "count": len(unsummarized_nodes),
            "rows": unsummarized_nodes
        }

    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc