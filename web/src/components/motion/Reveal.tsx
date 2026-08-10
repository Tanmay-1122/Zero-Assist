import type { CSSProperties, ReactNode } from "react";
import { useInView } from "../../lib/useInView";
import { cn } from "../../lib/cn";

interface RevealProps {
    children: ReactNode;
    /** Transition delay in ms — used for stagger sequences. */
    delay?: number;
    className?: string;
    id?: string;
}

/** Fade-and-rise scroll reveal. Wrapper only — no DOM semantics added. */
export function Reveal({ children, delay = 0, className, id }: RevealProps) {
    const { ref, inView } = useInView<HTMLDivElement>();
    const style = { "--reveal-delay": `${delay}ms` } as CSSProperties;

    return (
        <div
            ref={ref}
            id={id}
            className={cn("reveal", inView && "reveal--in", className)}
            style={style}
        >
            {children}
        </div>
    );
}
