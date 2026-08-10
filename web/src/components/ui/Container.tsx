import type { ElementType, ReactNode } from "react";
import { cn } from "../../lib/cn";

interface ContainerProps {
    as?: ElementType;
    variant?: "default" | "wide" | "narrow";
    className?: string;
    id?: string;
    children: ReactNode;
}

export function Container({
    as: Tag = "div",
    variant = "default",
    className,
    id,
    children,
}: ContainerProps) {
    const variantClass =
        variant === "wide" ? "container--wide" : variant === "narrow" ? "container--narrow" : "";
    return (
        <Tag id={id} className={cn("container", variantClass, className)}>
            {children}
        </Tag>
    );
}
