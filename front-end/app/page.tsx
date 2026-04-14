import Link from "next/link";
import { FiGrid, FiHome, FiFolder } from "react-icons/fi";
import NavFolder from "./components/NavFolder";

export default function Home() {
  return (
    <div className="app-container flex h-screen w-full">
        <div className="side-nav basis-[17%] p-[40px] bg-[#2F4BD8] [&_*]:text-white">
          <div className="navbar-starter">
            <div className="logo"> 
               <h1 className="font-bold text-[20px]">ORB</h1>
            </div>
            <div className="nav-links mt-[40px] flex flex-col gap-[10px]">
                <div>
                  <Link href="/" className="flex items-center gap-2 font-extralight text-[18px]">
                    <FiHome className="h-4 w-4" />
                    <span>Home</span>
                  </Link>
                </div>
                <div>
                  <Link href="/about" className="flex items-center gap-2 font-extralight text-[18px]">
                    <FiGrid className="h-4 w-4" />
                    <span>Dashboard</span>
                  </Link>
                </div>
            </div>
          </div>
          <div className="w-full h-[1px] bg-[#fff] mt-[20px] mb-[20px]"></div>
          <div className="code-sections">
              <NavFolder href="/code" label="benchmarks" Icon={FiFolder} />
              <NavFolder href="/code" label="compiler" Icon={FiFolder} />
              <NavFolder href="/code" label="cli" Icon={FiFolder} />
              <NavFolder href="/code" label="language-server" Icon={FiFolder} />
              <NavFolder href="/code" label="project-api" Icon={FiFolder} />
              <NavFolder href="/code" label="distribution" Icon={FiFolder} />
              <NavFolder href="/code" label="semtypes" Icon={FiFolder} />
          </div>
          <div className="settings">

          </div>
      </div>
      <div className="content p-[40px]">
          <div>
            <h1>Code Intelligence </h1>
          </div>
      </div>
      
    </div>
  );
}
