import { cn } from "../../lib/cn";

interface MarqueeProps {
    items: readonly string[];
    direction?: "ltr" | "rtl";
    className?: string;
}

export function Marquee({ items, direction = "ltr", className }: MarqueeProps) {
    const doubled = [...items, ...items];
    return (
        <div className={cn("marquee", className)} aria-hidden="true">
            <div className="marquee__track" data-direction={direction === "rtl" ? "rtl" : "ltr"}>
                {doubled.map((item, i) => (
                    <span className="marquee__item" key={`${item}-${i}`}>
                        {item}
                    </span>
                ))}
            </div>
        </div>
    );
}
