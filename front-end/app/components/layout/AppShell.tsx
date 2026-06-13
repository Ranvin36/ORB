import { ReactNode } from "react";
import SideNav from "./SideNav";

type AppShellProps = {
  children: ReactNode;
  className?: string;
  contentClassName?: string;
};

export default function AppShell({ children, className = "", contentClassName = "" }: AppShellProps) {
  return (
    <div className={`app-container flex w-full bg-[#F5F5F5] min-h-screen ${className}`}>
      <SideNav />

      <div className={`ml-[17%] w-[83%] flex-1 overflow-y-auto ${contentClassName}`}>
        {children}
      </div>
    </div>
  );
}