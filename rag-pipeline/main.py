import logging
import os
from dotenv import load_dotenv
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from rag.graph import get_rag_service
from rag.rabbitmq_consumer import create_default_consumer

from neo4j_client import (
    close_neo4j_driver,
    get_neo4j_driver,
    init_neo4j_driver,
    neo4j_health_check,
    run_query
)


if not logging.getLogger().handlers:
    logging.basicConfig(
        level=logging.INFO,
        format="%(levelname)s:%(name)s:%(message)s",
    )


app = FastAPI()
logger = logging.getLogger("rag_pipeline")
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


class MemoryStoreRequest(BaseModel):
    user_id: str
    text: str
    embedding: list[float]


class MemoryQueryRequest(BaseModel):
    user_id: str
    query_embedding: list[float]


def stream_talk_to_gpt_with_neo4j_tool(message: str):
    service = get_rag_service()
    # stream directly from the service (already yields SSE-formatted chunks)
    for chunk in service.stream(message):
        yield chunk


def talk_to_gpt_with_neo4j_tool(message: str) -> str:
    service = get_rag_service()
    return service.invoke(message)


def stream_talk_to_gpt_with_memory(user_id: str, message: str):
    service = get_rag_service()
    for chunk in service.stream_with_memory(user_id, message):
        yield chunk


def talk_to_gpt_with_memory(user_id: str, message: str) -> str:
    service = get_rag_service()
    return service.invoke_with_memory(user_id, message)


@app.on_event("startup")
async def startup_event():
    logger.info("Application startup: initializing Neo4j driver")
    init_neo4j_driver()
    # start rabbitmq consumer if configured
    global _rabbit_consumer
    _rabbit_consumer = None
    try:
        logger.info("Application startup: creating RabbitMQ consumer")
        consumer = await create_default_consumer()
        if consumer:
            _rabbit_consumer = consumer
            logger.info("Application startup: starting RabbitMQ consumer")
            await consumer.start()
        else:
            logger.info("Application startup: RabbitMQ consumer disabled")
    except Exception:
        # do not prevent app startup if consumer fails
        logger.exception("Application startup: RabbitMQ consumer failed to start")
        _rabbit_consumer = None


@app.on_event("shutdown")
async def shutdown_event():
    logger.info("Application shutdown: closing Neo4j driver")
    close_neo4j_driver()
    # stop rabbitmq consumer if running
    try:
        if globals().get("_rabbit_consumer"):
            logger.info("Application shutdown: stopping RabbitMQ consumer")
            await globals().get("_rabbit_consumer").stop()
    except Exception:
        logger.exception("Application shutdown: RabbitMQ consumer stop failed")
        pass


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

@app.get("/query")
async def query_get():
    return {"message": "Hello, World!"}


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
