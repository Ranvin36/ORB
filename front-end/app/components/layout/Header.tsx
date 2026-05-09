"use client";

import { FiSearch } from "react-icons/fi";
import { PiGitBranchLight } from "react-icons/pi";
import { IoMdRefresh } from "react-icons/io";
import { IoSettingsOutline, IoLogOutOutline } from "react-icons/io5";

export default function Header() {
  return (
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
            className="w-full bg-[#F0F0F0] border-none rounded-lg py-4 pl-12 pr-4 text-sm outline-none"
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
  );
}
