"use client";

import { useState } from "react";
import { PiBrainLight } from "react-icons/pi";
import { FiHome, FiFolder } from "react-icons/fi";
import { IoSettingsOutline  } from "react-icons/io5";
import { GrUploadOption } from "react-icons/gr";
import { IBM_Plex_Mono } from "next/font/google";

import NavFolder from "./components/NavFolder";
import { LLM_STREAM_URL } from "./constants/api";

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

function extractStreamText(data: string) {
  if (data.trim() === "[DONE]") {
    return "";
  }

  if (data === "") {
    return "\n";
  }

  try {
    const parsed = JSON.parse(data) as {
      content?: string;
      text?: string;
      delta?: string;
      token?: string;
      message?: string;
      response?: string;
    };

    const candidate =
      parsed.content ??
      parsed.text ??
      parsed.delta ??
      parsed.token ??
      parsed.message ??
      parsed.response;

    if (typeof candidate === "string") {
      return normalizeStreamText(candidate);
    }
  } catch {
    return normalizeStreamText(data);
  }

  return "";
}

export default function Home() {
  const [output, setOutput] = useState("");
  const [textInput, setTextInput] = useState("");
  const [isAsking, setIsAsking] = useState(false);

  const [messages, setMessages] = useState([]);

  const handleAsk = async () => {
    const prompt = textInput.trim();

    if (!prompt) {
      setOutput("Please enter a prompt first.");
      return;
    }

    setOutput("");
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

      while (true) {
        const { done, value } = await reader.read();

        if (done) {
          break;
        }

        const chunk = decoder.decode(value, { stream: true });
        const lines = chunk.split("\n");

        lines.forEach((line) => {
          if (line.startsWith("data:")) {
            const data = line.slice(5).replace(/^\s/, "");
            const text = extractStreamText(data);

            if (text) {
              setOutput((prev) => prev + text);
            }
          } else if (line.trim()) {
            setOutput((prev) => prev + normalizeStreamText(line));
          }
        });
      }
    } catch (error) {
      console.error("Failed to ask Orb:", error);
      setOutput("Could not reach Orb. Check that the backend is running and CORS is enabled for your frontend origin.");
    } finally {
      setIsAsking(false);
    }
  };

  return (
    <div className="app-container flex h-screen w-full">
        <div className="side-nav basis-[17%] p-[40px] bg-[#2F4BD8] [&_*]:text-white">
          <div className="navbar-starter">
            <div className="logo px-2"> 
               <h1 className="font-bold text-[20px]">ORB</h1>
            </div>
            <div className="nav-links mt-[40px]">
              <NavFolder href="/code" label="Home" Icon={FiHome} />
              <NavFolder href="/code" label="Ask Orb" Icon={PiBrainLight} />
            </div>
          </div>
          <div className="w-full h-[1px] bg-[#fff] mt-[20px] mb-[20px]"></div>
          <div className="code-sections h-[550px]">
              <NavFolder href="/code" label="benchmarks" Icon={FiFolder} />
              <NavFolder href="/code" label="compiler" Icon={FiFolder} />
              <NavFolder href="/code" label="cli" Icon={FiFolder} />
              <NavFolder href="/code" label="language-server" Icon={FiFolder} />
              <NavFolder href="/code" label="project-api" Icon={FiFolder} />
              <NavFolder href="/code" label="distribution" Icon={FiFolder} />
              <NavFolder href="/code" label="semtypes" Icon={FiFolder} />
          </div>
          <div className="w-full h-[1px] bg-[#fff] mt-[20px] mb-[20px]"></div>
          <div className="settings">
              <NavFolder href="/code" label="Settings" Icon={IoSettingsOutline} />
              <div className="navBtn py-1 rounded-[10px]">
                <NavFolder href="/logout" label="Log out" Icon={IoSettingsOutline} />
              </div>
          </div>
      </div>
      <div className="py-[40px] px-[90px] flex justify-center relative basis-[83%] h-full">
        <div className="w-[800px] flex justify-center items-center h-full">
          {!messages.length ? (
            <div className="text-center">
              <h1 className={`${ibmPlexMono.className} text-[50px] uppercase`}>Talk With Orb</h1>
              <p className="text-[#AFAFAF]">Orb turns your codebase into something you can actually talk to. Ask anything and get real answers traced directly from your graph and source code.</p>
            </div>
          ) : (
            <div>
              <div className="flex justify-end">
                  <div className="navBtn py-3 px-5 rounded-[10px]">
                      <p>Hi, What can you do? Long Explanation</p>
                  </div>
              </div>
              <div className="my-10">
                {renderFormattedOutput(output)}
              </div>
            </div>
          )}
        <div className="absolute bottom-[50px] left-0 right-0 flex justify-center">
          <div className="flex w-[820px] flex-col items-center gap-4">
            <div className="bg-[#fff] border-[#c3c3c3] border-1 w-full h-[90px] mx-auto rounded-[10px] flex justify-between items-center px-8">
            <div className="w-[80%]">
              <input
                type="text"
                placeholder="Ask Orb anything about your codebase..."
                value={textInput}
                onChange={(event) => setTextInput(event.target.value)}
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
      </div>
    </div>      
  </div>
</div>
  );
}
