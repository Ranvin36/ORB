"use client";

import { FiSearch } from "react-icons/fi";
import { PiGitBranchLight, PiFoldersLight, PiGraphLight, PiTreeStructureLight, PiStackLight } from "react-icons/pi";
import { IoMdRefresh } from "react-icons/io";
import { IoSettingsOutline, IoLogOutOutline } from "react-icons/io5";
import SideNav from "./components/layout/SideNav";

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
  return (
    <div className="app-container flex w-full bg-[#F5F5F5] min-h-screen">
      <SideNav />

      <div className="flex-1 overflow-y-auto">
        {/* Header */}
        <div className="flex items-center justify-between mb-10 bg-[#fff] py-[25px] px-[50px]">
          <div className="flex items-center gap-4">
            <h2 className="text-2xl font-semibold text-gray-800">Ballerina Lang</h2>
            <div className="flex items-center gap-1 px-2 py-1 bg-white border border-gray-200 rounded-md text-xs text-gray-500">
              <PiGitBranchLight className="text-sm" />
              <span>1.2.0</span>
            </div>
          </div>

          <div className="flex-1 max-w-2xl mx-10">
            <div className="relative">
              <FiSearch className="absolute left-4 top-1/2 -translate-y-1/2 text-gray-400" />
              <input
                type="text"
                placeholder="Search for any components in your codebase..."
                className="w-full bg-[#F0F0F0] border-none rounded-lg py-4 pl-12 pr-4 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
          </div>

          <div className="flex items-center gap-3">
            <button className="flex items-center gap-2 px-4 py-2 bg-white border border-gray-200 rounded-lg text-sm text-gray-600 hover:bg-gray-50 transition-colors shadow-sm">
              <IoMdRefresh className="text-lg" />
              <span>Re-Index</span>
            </button>
            <button className="p-2 bg-white border border-gray-200 rounded-lg text-gray-600 hover:bg-gray-50 transition-colors shadow-sm">
              <IoSettingsOutline className="text-xl" />
            </button>
            <button className="p-2 bg-white border border-gray-200 rounded-lg text-gray-600 hover:bg-gray-50 transition-colors shadow-sm">
              <IoLogOutOutline className="text-xl" />
            </button>
          </div>
        </div>

        {/* Project Orbit Section */}
        <div className="mb-12 px-[50px]">
          <h1 className="text-[23px] font-medium mb-1 tracking-tight">PROJECT ORBIT</h1>
          <p className="text-gray-400 text-sm font-light mb-5">Data dashboard UX patterns, and UI examples, plus ways to elevate.</p>

          <div className="grid grid-cols-5 gap-3">
            <StatCard icon={<PiStackLight />} label="Total Projects" value="01" />
            <StatCard icon={<PiFoldersLight />} label="Components" value="112" />
            <StatCard icon={<PiTreeStructureLight />} label="Relationships" value="75" />
            <StatCard icon={<PiGraphLight />} label="Graphs" value="30" />
            <StatCard icon={<PiGraphLight />} label="Graphs" value="30" />
          </div>
        </div>

        {/* Recent Indexes Section */}
        <div className="px-[50px]">
          <h2 className="text-[23px] font-medium mb-1 tracking-tight">RECENT INDEXES</h2>
          <div className="grid grid-cols-4 gap-6">
            {[1, 2, 3, 4].map((i) => (
              <div key={i} className="flex flex-col gap-3">
                <div className="bg-white rounded-2xl p-6 aspect-[1.3/1] flex items-center justify-center overflow-hidden">
                  <img src="/code_graph_preview.png" alt="Graph preview" className="w-full h-full object-contain" />
                </div>
                <p className="font-semibold text-gray-800 text-sm">ResolutionEngine</p>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
