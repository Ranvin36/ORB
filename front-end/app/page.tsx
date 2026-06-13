"use client";

import Image from "next/image";
import { useRouter } from "next/navigation";
import { PiFoldersLight, PiGraphLight, PiTreeStructureLight, PiStackLight } from "react-icons/pi";
import Header from "./components/layout/Header";
import AppShell from "./components/layout/AppShell";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";

function StatCard({ icon, label, value }: { icon: React.ReactNode, label: string, value: string }) {
  return (
    <div className="bg-white rounded-2xl p-6">
      <div className="flex items-center justify-between my-[-5px]">
        <span className="text-[20px] font-light">{label}</span>
        <div className="text-gray-400 bg-gray-50 p-2 rounded-full text-xl border border-gray-100">
          {icon}
        </div>
      </div>
      <div className="text-[50px] font-bold">{value}</div>
      <p className="text-gray-300 text-[13px] leading-tight font-light">Data dashboard UX patterns</p>
    </div>
  );
}

export default function Home() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [searchTerm, setSearchTerm] = useState("");
  const { data: stats = { totalProjects: "01", components: "0", relationships: "0", graphs: "0" } } = useQuery({
    queryKey: ["home-stats"],
    queryFn: async () => {
      const [relationshipsReq, nodeReq] = await Promise.all([
        fetch("http://localhost:8080/graph/relationship-count"),
        fetch("http://localhost:8080/graph/components-count"),
      ]);

      const [relationships, components] = await Promise.all([
        relationshipsReq.json(),
        nodeReq.json(),
      ]);

      return {
        totalProjects: "01",
        components,
        relationships,
        graphs: "0",
      };
    },
  });

  function handleSearchSubmit() {
    const query = searchTerm.trim();

    if (!query) {
      return;
    }

    queryClient.prefetchQuery({
      queryKey: ["search-results", query],
      queryFn: async () => {
        const searchData = await fetch(`http://localhost:8080/graph/search?query=${encodeURIComponent(query)}`);
        return searchData.json();
      },
    });

    router.push(`/search?query=${encodeURIComponent(query)}`);
  }

  return (
    <AppShell>
      <Header searchTerm={searchTerm} onSearchChange={setSearchTerm} onSearchSubmit={handleSearchSubmit} />

      <div className="mb-12 px-[50px]">
        <h1 className="text-[23px] font-medium mb-1 tracking-tight">PROJECT ORBIT</h1>
        <p className="text-gray-400 text-sm font-light mb-5">Data dashboard UX patterns, and UI examples, plus ways to elevate.</p>

        <div className="grid grid-cols-5 gap-3">
          <StatCard icon={<PiStackLight />} label="Total Projects" value={stats.totalProjects} />
          <StatCard icon={<PiFoldersLight />} label="Components" value={stats.components} />
          <StatCard icon={<PiTreeStructureLight />} label="Relationships" value={stats.relationships} />
          <StatCard icon={<PiGraphLight />} label="Graphs" value={stats.graphs} />
          <StatCard icon={<PiGraphLight />} label="Graphs" value={stats.graphs} />
        </div>
      </div>

      <div className="px-[50px]">
        <h2 className="text-[23px] font-medium mb-1 tracking-tight">RECENT INDEXES</h2>
        <div className="grid grid-cols-4 gap-6">
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="flex flex-col gap-3">
              <div className="bg-white rounded-2xl p-6 aspect-[1.3/1] flex items-center justify-center overflow-hidden">
                <Image src="/code_graph_preview.png" alt="Graph preview" width={200} height={150} className="w-full h-full object-contain" />
              </div>
              <p className="font-semibold text-gray-800 text-sm">ResolutionEngine</p>
            </div>
          ))}
        </div>
      </div>
    </AppShell>
  );
}
