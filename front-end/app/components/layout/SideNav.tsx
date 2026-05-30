"use client";

import { PiBrainLight } from "react-icons/pi";
import { FiHome, FiFolder } from "react-icons/fi";
import { IoSettingsOutline } from "react-icons/io5";
import NavFolder from "../NavFolder";

export default function SideNav() {
  return (
    <div className="fixed top-0 left-0 h-screen side-nav w-[17%] p-[40px] bg-[#2F4BD8] [&_*]:text-white z-50">
      <div className="navbar-starter">
        <div className="logo px-2">
          <h1 className="font-bold text-[20px]">ORB</h1>
        </div>
        <div className="nav-links mt-[40px]">
          <NavFolder href="/" label="Home" Icon={FiHome} />
          <NavFolder href="/ask-orb" label="Ask Orb" Icon={PiBrainLight} />
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
  );
}
