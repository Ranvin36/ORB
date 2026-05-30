"use client";

import { useEffect, useState } from "react";
import { IBM_Plex_Mono } from "next/font/google";
import { GrUploadOption } from "react-icons/gr";
import SideNav from "../components/layout/SideNav";
import { LLM_STREAM_URL } from "../constants/api";

const ibmPlexMono = IBM_Plex_Mono({
  subsets: ["latin"],
  weight: ["400", "500", "700"],
});

function renderFormattedOutput(text: string) {
  return text.split("\n").map((line, lineIndex) => {
    if (line.length === 0) {
      return <div key={`line-${lineIndex}`} className="h-6" aria-hidden="true" />;
    }

    const parts: React.ReactNode[] = [];
    const boldPattern = /\*\*(.+?)\*\*/g;
    let lastIndex = 0;
    let match;

    while ((match = boldPattern.exec(line)) !== null) {
      if (match.index > lastIndex) {
        parts.push(line.slice(lastIndex, match.index));
      }

      parts.push(<strong key={`${lineIndex}-bold-${match.index}`}>{match[1]}</strong>);
      lastIndex = match.index + match[0].length;
    }

    if (lastIndex < line.length) {
      parts.push(line.slice(lastIndex));
    }

    return (
      <div key={`line-${lineIndex}`}>
        {parts}
      </div>
    );
  });
}

function normalizeStreamText(value: string) {
  return value
    .replace(/\\r\\n/g, "\n")
    .replace(/\\n/g, "\n")
    .replace(/\\t/g, "\t");
}

export default function AskOrb() {
  const [textInput, setTextInput] = useState("");
  const [isAsking, setIsAsking] = useState(false);

  const [messages, setMessages] = useState<{ id: string; role: string; content: string }[]>([]);

  const handleAsk = async () => {
    const prompt = textInput.trim();

    if (!prompt || isAsking) {
      return;
    }

    setIsAsking(true);

    try {
      const res = await fetch(LLM_STREAM_URL, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          message: prompt,
          stream: true
        }),
      });

      if (!res.ok) {
        throw new Error(`Request failed with status ${res.status}`);
      }
      const reader = res.body?.getReader();
      if (!reader) {
        throw new Error("Response body is empty");
      }
      const decoder = new TextDecoder();

      const userMsg = {
        id: crypto.randomUUID(),
        role: "user",
        content: prompt,
      };

      const assistantId = crypto.randomUUID();

      const assistantMsg = {
        id: assistantId,
        role: "assistant",
        content: "",
      };

      setMessages((prev) => [...prev, userMsg, assistantMsg]);
      setTextInput("");

      let buffer = ""; // Buffer for incomplete chunks

      while (true) {
        const { done, value } = await reader.read();

        if (done) {
          break;
        }

        buffer += decoder.decode(value, { stream: true });
        let lines = buffer.split("\n");
        buffer = lines.pop() || "";

        for (const line of lines) {
          if (line.startsWith("data:")) {
            const data = line.slice(5);
            const trimmedData = data.trim();
            if (trimmedData === "[DONE]") return;

            let text = data;
            try {
              const parsed = JSON.parse(data);
              text = parsed.token || parsed.content || data;
            } catch (e) {
              // Use raw data (preserving spaces)
            }

            if (text) {
              setMessages((prev) =>
                prev.map((msg) =>
                  msg.id === assistantId
                    ? { ...msg, content: msg.content + text }
                    : msg
                )
              );
            }
          }
        }
      }
    } catch (error) {
      console.error("Failed to ask Orb:", error);
      setMessages((prev) => [
        ...prev,
        {
          id: crypto.randomUUID(),
          role: "assistant",
          content: "Could not reach Orb. Check that the backend is running and CORS is enabled for your frontend origin.",
        },
      ]);
    } finally {
      setIsAsking(false);
    }
  };

  return (
    <div className="app-container flex w-full">
      <SideNav />
      <div className="py-[40px] px-[90px] flex flex-col items-center relative ml-[17%] w-[83%] min-h-screen">
        <div className="w-full max-w-[800px] flex flex-col flex-1">
          {!messages.length ? (
            <div className="flex-1 flex flex-col justify-center items-center text-center">
              <h1 className={`${ibmPlexMono.className} text-[50px] uppercase`}>Talk With Orb</h1>
              <p className="text-[#AFAFAF]">Orb turns your codebase into something you can actually talk to. Ask anything and get real answers traced directly from your graph and source code.</p>
            </div>
          ) : (
            <div className="w-full flex-1 pb-32">
              {messages && messages.length > 0 && messages.map((msg) => (
                <div key={msg.id} className="my-6 w-full">
                  {msg.role === "user" ? (
                    <div className="flex justify-end">
                      <div className="navBtn py-3 px-5 rounded-[10px]">
                        <p>{msg.content}</p>
                      </div>
                    </div>
                  ) : (
                    <div className="my-4 whitespace-pre-wrap break-words overflow-x-hidden">
                      {msg.content === "" && isAsking ? (
                        <div>
                          <p>Thinking...</p>
                        </div>
                      ) : (
                        renderFormattedOutput(msg.content)
                      )}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
          <div className="sticky bottom-8 z-10 w-full">
            <div className="flex w-[820px] flex-col items-center gap-4">
              <div className="bg-[#fff] border-[#c3c3c3] border-1 w-full h-[90px] mx-auto rounded-[10px] flex justify-between items-center px-8">
                <div className="w-[80%]">
                  <input
                    type="text"
                    placeholder="Ask Orb anything about your codebase..."
                    value={textInput}
                    onChange={(event) => setTextInput(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') {
                        handleAsk();
                      }
                    }}
                    className="w-[100%] h-[60%] rounded-[10px] py-2 text-[13px] focus:outline-none"
                  />
                </div>
                <div>
                  <button type="button" onClick={handleAsk} aria-label="Send prompt" disabled={isAsking}>
                    <GrUploadOption className="text-[24px] text-[#c2c2c2] cursor-pointer" />
                  </button>
                </div>
              </div>
            </div>
            <div className="bg-[#ededed] bottom-[0px] h-[30px] w-full px-[10px]"  >

            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
