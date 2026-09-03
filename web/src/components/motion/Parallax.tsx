import type { ReactNode } from "react";
import { useParallax } from "../../lib/useParallax";
import { cn } from "../../lib/cn";

interface ParallaxProps {
    speed?: number;
    className?: string;
    children?: ReactNode;
}

/** Subtle scroll parallax wrapper. Decorative layers only. */
export function Parallax({ speed = 0.08, className, children }: ParallaxProps) {
    const ref = useParallax<HTMLDivElement>(speed);
    return (
        <div ref={ref} className={cn("parallax", className)}>
            {children}
        </div>
    );
}
