import os
import json
from dotenv import load_dotenv
from typing import Any

from fastapi import FastAPI, HTTPException
from openai import OpenAI
from pydantic import BaseModel, Field

from neo4j_client import (
    close_neo4j_driver,
    get_neo4j_driver,
    init_neo4j_driver,
    neo4j_health_check,
    run_query
)


app = FastAPI()
load_dotenv()
openai_client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))


class Neo4jQueryRequest(BaseModel):
    cypher: str
    params: dict[str, Any] = Field(default_factory=dict)


class LlmQueryRequest(BaseModel):
    message: str

def talk_to_gpt(message: str) -> str:
    res = openai_client.chat.completions.create(
        model="gpt-4.1-nano",
        messages=[{"role": "user", "content": message}]
    )
    return res.choices[0].message.content


def talk_to_gpt_with_neo4j_tool(message: str) -> str:
    schema_context = """
    ### Node Labels and Properties:
    (:Class {name: string, type: string, parentClass: string})
    (:Method {id: string, className: string, kind: string})

    ### Relationship Types:
    (:Method)-[:CALLS]->(:Method)
    (:Class)-[:EXTENDS]->(:Class)
    (:Class)-[:HAS_METHOD]->(:Method)
    """
    tools = [
        {
            "type": "function",
            "function": {
                "name": "query_neo4j",
                "description": (
                    "Retrieve the relevant subgraph context from Neo4j for the user query. "
                    "Return read-only Cypher that fetches connected nodes, relationships, "
                    "and multi-hop paths needed to answer the question."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "cypher": {
                            "type": "string",
                            "description": (
                                "Read-only Cypher query that extracts the relevant subgraph context. "
                                "Prefer queries that return paths (p), nodes, and relationships."
                            ),
                        },
                        "params": {
                            "type": "object",
                            "description": "Query parameters as a JSON object.",
                        },
                    },
                    "required": ["cypher"],
                },
            },
        }
    ]

    messages = [
        {
            "role": "system",
            "content": f"You are a code intelligence assistant. Use the following graph schema to answer user queries:\n{schema_context}\n\nWorkflow: " + 
            "1. Query Neo4j for structural context. "
            "2. The system will automatically fetch code snippets if you return 'path', 'start_line', and 'end_line'."
            "3. Explain the code using both graph and source context.",
        },
        {"role": "user", "content": message},
    ]

    # Allow the model to recover from Cypher errors by retrying tool calls.
    for _ in range(4):
        response = openai_client.chat.completions.create(
            model="gpt-4.1-nano",
            messages=messages,
            tools=tools,
            tool_choice="auto",
        )

        assistant_message = response.choices[0].message
        tool_calls = assistant_message.tool_calls or []

        messages.append(
            {
                "role": "assistant",
                "content": assistant_message.content or "",
                "tool_calls": [
                    {
                        "id": call.id,
                        "type": call.type,
                        "function": {
                            "name": call.function.name,
                            "arguments": call.function.arguments,
                        },
                    }
                    for call in tool_calls
                ],
            }
        )

        if not tool_calls:
            return assistant_message.content or ""

        for call in tool_calls:
            if call.function.name != "query_neo4j":
                continue

            try:
                args = json.loads(call.function.arguments or "{}")
            except json.JSONDecodeError:
                args = {}

            cypher = args.get("cypher", "")
            params = args.get("params", {})

            try:
                rows = run_query(cypher, params, read_only=True)
                tool_result = {"count": len(rows), "rows": rows}
            except Exception as exc:
                tool_result = {
                    "error": str(exc),
                    "hint": "Fix the Cypher and try query_neo4j again.",
                }

            messages.append(
                {
                    "role": "tool",
                    "tool_call_id": call.id,
                    "content": json.dumps(tool_result),
                }
            )

    return "I could not produce a reliable answer after multiple query attempts."


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


@app.post("/llm/query")
async def llm_query(payload: LlmQueryRequest):
    try:
        answer = talk_to_gpt_with_neo4j_tool(payload.message)
        return {"answer": answer}
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"LLM query failed: {exc}") from exc
