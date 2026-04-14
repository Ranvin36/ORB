import Link from "next/link";
import { type ComponentType } from "react";

type NavFolderProps = {
	href: string;
	label: string;
	Icon: ComponentType<{ className?: string }>;
	className?: string;
	iconClassName?: string;
};

export default function NavFolder({
	href,
	label,
	Icon,
	className = "my-[13px] flex items-center gap-2 font-extralight text-[18px]",
	iconClassName = "h-5 w-5",
}: NavFolderProps) {
	return (
		<Link href={href} className={className}>
			<Icon className={iconClassName} />
			<span>{label}</span>
		</Link>
	);
}

