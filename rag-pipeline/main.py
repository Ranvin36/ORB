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

class CodeFetcher:
    @staticmethod
    def get_code_snippet(file_path: str, start_line: int, end_line: int) -> str:
        """
        Reads a specific line range from a local file to retrieve a code snippet.
        """
        try:
            # Handle Windows paths if running on Linux, or vice versa
            # This is a simple replacement, you might need more robust path handling
            normalized_path = file_path.replace('\\', '/')
            
            # If the path is absolute but from a different OS, you might need to map it
            # e.g., if "C:/Users/..." is stored but you are on Linux
            if ":" in normalized_path and not os.path.isabs(normalized_path):
                 # This is a heuristic for Windows absolute paths on Linux
                 pass

            if not os.path.exists(normalized_path):
                return f"Error: File not found at {normalized_path}"
                
            with open(normalized_path, 'r', encoding='utf-8', errors='ignore') as f:
                lines = f.readlines()
                # Adjust for 1-based indexing from Neo4j to 0-based for Python lists
                start_idx = max(0, start_line - 1)
                end_idx = min(len(lines), end_line)
                snippet_lines = lines[start_idx:end_idx]
                return ''.join(snippet_lines)
        except Exception as e:
            return f"Error reading file: {str(e)}"


def _get_row_value(row: dict[str, Any], *candidates: str) -> Any:
    for key in candidates:
        if key in row and row[key] is not None:
            return row[key]
    return None


def _extract_code_location(row: dict[str, Any]) -> tuple[Any, Any, Any]:
    path = _get_row_value(row, "filePath", "path")
    start_line = _get_row_value(row, "startLine", "start_line")
    end_line = _get_row_value(row, "endLine", "end_line")

    # Fallback for Neo4j column names like "m.filePath" or "c.startLine".
    if path is None:
        for key, value in row.items():
            if key.endswith(".filePath") and value is not None:
                path = value
                break
    if start_line is None:
        for key, value in row.items():
            if (key.endswith(".startLine") or key.endswith(".start_line")) and value is not None:
                start_line = value
                break
    if end_line is None:
        for key, value in row.items():
            if (key.endswith(".endLine") or key.endswith(".end_line")) and value is not None:
                end_line = value
                break

    return path, start_line, end_line

def talk_to_gpt_with_neo4j_tool(message: str) -> str:
    schema_context = """
    ### Node Labels and Properties:
    (:Class {name: string, type: string, parentClass: string, filePath: string})
    (:Method {id: string, className: string, kind: string, startLine: int, endLine: int, filePath: string})

    ### Relationship Types:
    (:Method)-[:CALLS]->(:Method)
    (:Class)-[:EXTENDS]->(:Class)
    (:Class)-[:IMPLEMENTS]->(:Class)
    (:Class)-[:HAS_METHOD]->(:Method)

    ### Important:
    When you need to explain code, ALWAYS alias your Cypher RETURN fields exactly as:
    filePath, startLine, endLine

    ### CRITICAL QUERYING INSTRUCTIONS:
    1. **Case-Insensitive Matching**: Always use `toLower()` for string comparisons (e.g., `WHERE toLower(m.id) = toLower($methodId)`).
    2. **Composite Names (e.g., 'Main.main')**: 
       - The `id` property for a Method node is typically in the format "ClassName.methodName".
       - To find a method like 'Main.main', you should query directly on the `id` property.
    - Example: `MATCH (m:Method) WHERE toLower(m.id) = toLower('Main.main') RETURN m.filePath AS filePath, m.startLine AS startLine, m.endLine AS endLine`
       - If you need to find methods within a specific class, you can combine `className` and a partial match on `id` or use `className` directly.
         - Example: `MATCH (m:Method) WHERE toLower(m.className) = toLower('Main') AND toLower(m.id) CONTAINS toLower('.main') RETURN m.filePath AS filePath, m.startLine AS startLine, m.endLine AS endLine`
     3. **Code Retrieval**: When explaining code, ALWAYS return aliased fields exactly as filePath, startLine, endLine.
    
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
            "2. The system will automatically fetch code snippets if you return 'filePath', 'startLine', and 'endLine' with exact aliases (e.g., m.filePath AS filePath)."
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
                enriched_rows = []
                for row in rows:
                    print(row)
                    path, start_line, end_line = _extract_code_location(row)
                    print(f"Processing row with path: {path}, start_line: {start_line}, end_line: {end_line}")

                    if path and start_line is not None and end_line is not None:
                        code_snippet = CodeFetcher.get_code_snippet(path, int(start_line), int(end_line))
                        row['code_content'] = code_snippet

                        print(f"Fetched code snippet for {path} lines {start_line}-{end_line}")

                    enriched_rows.append(row)
                if not enriched_rows:
                    tool_result = {
                        "count": 0, 
                        "rows": [], 
                        "hint": "No results found. Try a case-insensitive search with toLower() or use CONTAINS for partial matches."
                    }
                else:
                    tool_result = {"count": len(enriched_rows), "rows": enriched_rows}
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
