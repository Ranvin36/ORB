import { PiBrainLight } from "react-icons/pi";
import { FiGrid, FiHome, FiFolder } from "react-icons/fi";
import { IoSettingsOutline  } from "react-icons/io5";
import NavFolder from "./components/NavFolder";

export default function Home() {
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
      <div className="p-[40px] flex justify-center items-center basis-[83%] h-full">
          <div className="text-center max-w-[700px]">
            <h1 className="text-[50px]">Talk With Orb</h1>
            <p className="text-[#AFAFAF]">Orb turns your codebase into something you can actually talk to. Ask anything and get real answers traced directly from your graph and source code.</p>
          </div>
      </div>
      
    </div>
  );
}
