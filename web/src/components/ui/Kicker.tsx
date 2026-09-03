import { cn } from "../../lib/cn";

interface KickerProps {
    index?: string;
    tone?: "teal" | "gold";
    className?: string;
    children: string;
}

export function Kicker({ index, tone = "teal", className, children }: KickerProps) {
    return (
        <span className={cn("kicker", tone === "gold" && "kicker--gold", className)}>
            {index ? (
                <>
                    <span className="kicker__index">{index}</span>
                    {children}
                </>
            ) : (
                children
            )}
        </span>
    );
}
