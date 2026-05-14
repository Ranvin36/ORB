"use client";

import { useEffect, useState, useCallback, useRef } from "react";
import { useParams, useRouter } from "next/navigation";
import SideNav from "../../components/layout/SideNav";
import Header from "../../components/layout/Header";

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

type GraphNode = {
  id: string;
  label: string;
  x: number;
  y: number;
};

type GraphEdge = {
  from: string;
  to: string;
  label?: string;
};

type FunctionInfo = {
  name: string;
  description: string;
};

type ComponentData = {
  name: string;
  filePath: string;
  summary: string;
  functions: FunctionInfo[];
  graph: {
    nodes: GraphNode[];
    edges: GraphEdge[];
  };
};

/* ------------------------------------------------------------------ */
/*  Mini Dependency Graph (SVG)                                        */
/* ------------------------------------------------------------------ */

function DependencyGraph({
  nodes,
  edges,
}: {
  nodes: GraphNode[];
  edges: GraphEdge[];
}) {
  /* Draw edges with labels and arrows between nodes */
  return (
    <svg
      viewBox="0 0 380 320"
      className="w-full h-full"
      style={{ minHeight: 260 }}
    >
      <defs>
        <marker
          id="arrowhead"
          markerWidth="8"
          markerHeight="6"
          refX="8"
          refY="3"
          orient="auto"
        >
          <polygon points="0 0, 8 3, 0 6" fill="#b0b0b0" />
        </marker>
      </defs>

      {/* Edges */}
      {edges.map((edge, i) => {
        const from = nodes.find((n) => n.id === edge.from);
        const to = nodes.find((n) => n.id === edge.to);
        if (!from || !to) return null;

        const midX = (from.x + to.x) / 2;
        const midY = (from.y + to.y) / 2;

        return (
          <g key={`edge-${i}`}>
            <line
              x1={from.x}
              y1={from.y + 18}
              x2={to.x}
              y2={to.y - 18}
              stroke="#c4c4c4"
              strokeWidth="1.5"
              markerEnd="url(#arrowhead)"
            />
            {edge.label && (
              <text
                x={midX + 12}
                y={midY}
                textAnchor="start"
                fontSize="9"
                fill="#888"
                fontStyle="italic"
              >
                {edge.label}
              </text>
            )}
          </g>
        );
      })}

      {/* Nodes */}
      {nodes.map((node) => {
        const textLen = node.label.length * 7.5 + 30;
        const boxW = Math.max(textLen, 100);
        const boxH = 36;

        return (
          <g key={node.id}>
            <rect
              x={node.x - boxW / 2}
              y={node.y - boxH / 2}
              width={boxW}
              height={boxH}
              rx="8"
              fill="#e8e8e8"
              stroke="#d0d0d0"
              strokeWidth="1"
            />
            <text
              x={node.x}
              y={node.y + 4}
              textAnchor="middle"
              fontSize="11"
              fontWeight="500"
              fill="#333"
            >
              {node.label}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

/* ------------------------------------------------------------------ */
/*  Placeholder data (used when API is unavailable)                    */
/* ------------------------------------------------------------------ */

function makePlaceholder(id: string): ComponentData {
  const name = decodeURIComponent(id)
    .replace(/[-_]/g, " ")
    .replace(/\b\w/g, (c) => c.toUpperCase());

  return {
    name,
    filePath: `compiler/src/main/${name.replace(/ /g, "")}.java`,
    summary:
      "A resolution engine is the logical core of a language like Prolog that uses a process called SLD resolution to determine if a goal is true by matching it against a database of facts and rules. It operates through unification, a sophisticated pattern-matching technique that binds variables to values to make two logical expressions identical, and backtracking, which allows the engine to retreat from dead-end logical paths and try alternative branches. Essentially, the engine treats a program as a set of mathematical axioms and attempts a \"proof by contradiction\"—assuming the user's query is false and searching for a logical inconsistency within the defined rules to prove it is actually true",
    functions: [
      {
        name: "createGraph",
        description:
          "Used to build the entire dependency graph, and everything done is simply summarized here",
      },
      {
        name: "anotherFunction",
        description:
          "Used to build the entire dependency graph, and everything done is simply summarized here",
      },
    ],
    graph: {
      nodes: [
        { id: "1", label: "PackageResolver", x: 190, y: 40 },
        { id: "2", label: "Resolution Engine", x: 190, y: 160 },
        { id: "3", label: "createGraph", x: 280, y: 280 },
      ],
      edges: [
        { from: "1", to: "2", label: "Calls to resolve versions" },
        { from: "2", to: "3", label: "Build complete\ndependency graph" },
      ],
    },
  };
}

/* ------------------------------------------------------------------ */
/*  Page Component                                                      */
/* ------------------------------------------------------------------ */

export default function ComponentPage() {
  const params = useParams();
  const router = useRouter();
  const id = params.id as string;

  const [searchTerm, setSearchTerm] = useState("");
  const [data, setData] = useState<ComponentData | null>(null);
  const [loading, setLoading] = useState(true);

  /* Fetch component details from API, fall back to placeholder data */
  useEffect(() => {
    let cancelled = false;

    async function fetchComponent() {
      try {
        const res = await fetch(
          `http://localhost:8080/graph/component/${encodeURIComponent(id)}`
        );
        if (!res.ok) throw new Error("API error");
        const json = await res.json();

        if (!cancelled) {
          setData({
            name: json.name ?? id,
            filePath: json.filePath ?? "",
            summary: json.summary ?? "",
            functions: json.functions ?? [],
            graph: json.graph ?? { nodes: [], edges: [] },
          });
        }
      } catch {
        /* Use placeholder when the backend is down */
        if (!cancelled) setData(makePlaceholder(id));
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    fetchComponent();
    return () => {
      cancelled = true;
    };
  }, [id]);

  function handleSearchSubmit() {
    const q = searchTerm.trim();
    if (!q) return;
    router.push(`/search?query=${encodeURIComponent(q)}`);
  }

  /* ---------------------------------------------------------------- */
  /*  Render                                                           */
  /* ---------------------------------------------------------------- */

  return (
    <div className="app-container flex w-full bg-[#F5F5F5] min-h-screen">
      <SideNav />

      <div className="flex-1 overflow-y-auto">
        <Header
          searchTerm={searchTerm}
          onSearchChange={setSearchTerm}
          onSearchSubmit={handleSearchSubmit}
        />

        {loading ? (
          <div className="px-[50px] py-16 flex items-center justify-center">
            <div className="flex flex-col items-center gap-4">
              <div className="w-10 h-10 border-[3px] border-[#2F4BD8] border-t-transparent rounded-full animate-spin" />
              <span className="text-gray-400 text-sm font-light">
                Loading component…
              </span>
            </div>
          </div>
        ) : data ? (
          <div className="px-[50px] pb-16">
            {/* Breadcrumb */}
            <p className="text-[13px] text-gray-400 font-light mb-1 tracking-wide">
              {data.filePath}
            </p>

            {/* Title + Graph */}
            <div className="flex gap-10 items-start">
              {/* Left: Summary */}
              <div className="flex-1 min-w-0">
                <h1 className="text-[28px] font-bold text-gray-900 mt-4 mb-2 tracking-tight">
                  Resolution Engine
                </h1>

                {/* Summary */}
                <h2 className="text-[22px] font-medium text-gray-400 mb-2">
                  Summary
                </h2>
                <p className="text-[16px] leading-[1.75] text-gray-600 font-light max-w-[820px]">
                  {data.summary}
                </p>
                {/* Functions & Why? */}
                <div className="max-w-[820px]">
                <h2 className="text-[20px] font-medium text-gray-400 mb-5 mt-8">
                    Functions &amp; Why?
                </h2>

                <div className="flex flex-col gap-6">
                    {data.functions.map((fn, idx) => (
                    <div key={idx} className="group">
                        <h3 className="text-[18px] font-semibold text-gray-900 mb-1">
                        {fn.name}
                        </h3>
                        <p className="text-[16px] leading-[1.7] text-gray-500 font-light">
                        {fn.description}
                        </p>
                    </div>
                    ))}
                </div>
                </div>
              </div>

              {/* Right: Dependency Graph Card */}
              <div className="shrink-0 w-[470px]">
                <button className="ml-auto block mb-3 px-4 py-[6px] rounded-full border border-gray-300 text-[12px] text-gray-500 font-light hover:bg-white hover:shadow-sm transition-all">
                  Show full visualizer
                </button>
                <div className="bg-[#F0F0F0] rounded-2xl p-4 border border-gray-200/60">
                  {data.graph.nodes.length > 0 ? (
                    <DependencyGraph
                      nodes={data.graph.nodes}
                      edges={data.graph.edges}
                    />
                  ) : (
                    <div className="flex items-center justify-center h-[260px] text-gray-400 text-sm font-light">
                      No graph data available
                    </div>
                  )}
                </div>
              </div>
            </div>
          </div>
        ) : (
          <div className="px-[50px] py-16 text-center text-gray-400 font-light">
            Component not found.
          </div>
        )}
      </div>
    </div>
  );
}