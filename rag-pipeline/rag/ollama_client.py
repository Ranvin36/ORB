import os
from typing import Iterable

import httpx
from langchain_core.messages import AIMessage


OLLAMA_URL = os.getenv("OLLAMA_URL", "http://localhost:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen2.5-coder:7b")


def _messages_to_prompt(messages: Iterable):
    parts = []
    for m in messages:
        # Accept objects with 'content' and optional 'role' or use str(m)
        role = getattr(m, "role", None)
        content = getattr(m, "content", None)
        if content is None:
            try:
                content = str(m)
            except Exception:
                content = ""
        if role:
            parts.append(f"[{role}] {content}")
        else:
            parts.append(str(content))
    return "\n\n".join(parts)


def generate_from_messages(messages: Iterable, model: str | None = None, timeout: int = 30, stream: bool = False):
    """Call Ollama HTTP API to generate a text response from messages.

    This is intentionally minimal: it sends a prompt constructed from the
    provided messages and returns the raw response text. Adjust as needed
    for structured JSON responses or streaming.
    """
    model = model or OLLAMA_MODEL
    prompt = _messages_to_prompt(messages)
    url = OLLAMA_URL.rstrip("/") + "/api/generate"

    payload = {"model": model, "prompt": prompt}

    try:
        if stream:
            # Stream the HTTP response and yield text chunks
            with httpx.stream("POST", url, json=payload, timeout=timeout) as resp:
                resp.raise_for_status()
                for chunk in resp.iter_text(chunk_size=1024):
                    if chunk:
                        yield chunk
            return

        # non-streaming path: return full response text
        with httpx.Client(timeout=timeout) as client:
            resp = client.post(url, json=payload)
            resp.raise_for_status()
            # Try to return plain text if available, else fall back to JSON string
            content_type = resp.headers.get("Content-Type", "")
            if "application/json" in content_type:
                try:
                    data = resp.json()
                    # Try common keys
                    for key in ("text", "generated", "output", "result", "completion"):
                        if key in data:
                            return data[key] if isinstance(data[key], str) else str(data[key])
                    # If results array
                    if "results" in data and isinstance(data["results"], list) and data["results"]:
                        return str(data["results"][0])
                    return str(data)
                except Exception:
                    return resp.text
            return resp.text
    except Exception as exc:
        raise RuntimeError(f"Ollama generate failed: {exc}")
