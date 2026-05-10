"use client";

import SideNav from "../components/layout/SideNav";
import Header from "../components/layout/Header";

const searchResults = [
  {
    title: "Resolution Engine",
    description: "The resolution engine recursively crawls your project's requirements to build a comprehensive dependency graph, mapping out how libraries and their sub-dependencies interact."
  },
  {
    title: "DefaultResolver",
    description: "The resolution engine recursively crawls your project's requirements to build a comprehensive dependency graph, mapping out how libraries and their sub-dependencies interact."
  },
  {
    title: "PackageResolver",
    description: "The resolution engine recursively crawls your project's requirements to build a comprehensive dependency graph, mapping out how libraries and their sub-dependencies interact."
  },
  {
    title: "RemotePackageRepository",
    description: "The resolution engine recursively crawls your project's requirements to build a comprehensive dependency graph, mapping out how libraries and their sub-dependencies interact."
  },
  {
    title: "BlendedManifest",
    description: "The resolution engine recursively crawls your project's requirements to build a comprehensive dependency graph, mapping out how libraries and their sub-dependencies interact."
  }
];

export default function SearchPage() {
  return (
    <div className="app-container flex w-full bg-[#F5F5F5] min-h-screen">
      <SideNav />

      <div className="flex-1 overflow-y-auto">
        <Header />

        <div className="px-[50px] pb-10">
          <h1 className="text-[23px] font-medium mb-8 tracking-tight uppercase">SEARCH RESULTS FOR RESOLUTION ENGINE</h1>

          <div className="flex flex-col gap-8">
            {searchResults.map((result, index) => (
              <div key={index} className="flex flex-col gap-2">
                <h2 className="text-[22px] font-medium text-gray-800 tracking-tight">{result.title}</h2>
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
