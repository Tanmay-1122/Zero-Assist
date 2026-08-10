import type { ReactNode } from "react";
import { cn } from "../../lib/cn";

interface VisualPlaceholderProps {
    /** Short label rendered in the corner, e.g. "capability — voice". */
    label: string;
    className?: string;
    children?: ReactNode;
}

/**
 * Elegant procedural stand-in for future artwork. Renders a labeled
 * gradient composition that reads as intentional design, not filler.
 */
export function VisualPlaceholder({ label, className, children }: VisualPlaceholderProps) {
    return (
        <div
            className={cn("placeholder", className)}
            role="img"
            aria-label={`${label} — placeholder`}
        >
            {children}
            <span className="placeholder__label">Todo · {label}</span>
        </div>
    );
}
