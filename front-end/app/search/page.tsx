"use client";

import { useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import SideNav from "../components/layout/SideNav";
import Header from "../components/layout/Header";


export default function SearchPage() {
  const searchParams = useSearchParams();
  const queryFromUrl = searchParams.get("query") ?? "";
  const [searchTerm, setSearchTerm] = useState(queryFromUrl);
  const [searchResults, setSearchResults] = useState<any>([]);

  useEffect(() => {
    setSearchTerm(queryFromUrl);
  }, [queryFromUrl]);

  async function getSearchResults() {
    if (searchTerm.length <= 1) {
      setSearchResults([]);
      return;
    }

    try {
      const searchData = await fetch(`http://localhost:8080/graph/search?query=${encodeURIComponent(searchTerm)}`);
      const searchResults = await searchData.json();
      console.log("Search results:", searchResults);
      setSearchResults(searchResults);
    } catch (error) {
      console.error("Error fetching search results:", error);
    }
  }

  useEffect(() => {
    getSearchResults();
  }, [searchTerm]);

  return (
    <div className="app-container flex w-full bg-[#F5F5F5] min-h-screen">
      <SideNav />

      <div className="flex-1 overflow-y-auto">
        <Header searchTerm={searchTerm} onSearchChange={setSearchTerm} />

        <div className="px-[50px] pb-10">
          <h1 className="text-[23px] font-light mb-8 tracking-tight">SEARCH RESULTS FOR : {searchTerm}</h1>

          <div className="flex flex-col gap-8">
            {searchResults && searchResults.length >0 && searchResults.map((result:any, index:any) => (
              <div key={index} className="flex flex-col gap-2">
                <h2 className="text-[22px] font-medium text-gray-800 tracking-tight">{result.methodId}</h2>
                <p className="text-gray-500 text-[18px] font-light leading-snug">
                  {result.description}
                </p>
                {index < searchResults.length - 1 && (
                  <div className="w-full h-[1px] bg-gray-300 mt-3"></div>
                )}
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
