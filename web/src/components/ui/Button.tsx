import type { AnchorHTMLAttributes, ButtonHTMLAttributes, ReactNode } from "react";
import { cn } from "../../lib/cn";

type Variant = "primary" | "ghost";
type Size = "sm" | "md" | "lg";

interface BaseProps {
    variant?: Variant;
    size?: Size;
    className?: string;
    children: ReactNode;
}

type ButtonAsButton = BaseProps & ButtonHTMLAttributes<HTMLButtonElement> & { href?: undefined };
type ButtonAsLink = BaseProps & AnchorHTMLAttributes<HTMLAnchorElement> & { href: string };
export type ButtonProps = ButtonAsButton | ButtonAsLink;

const BASE_KEYS = ["variant", "size", "className", "children", "href"] as const;

function restOf<T extends Record<string, unknown>>(props: T) {
    const rest = { ...props };
    for (const key of BASE_KEYS) delete rest[key];
    return rest;
}

export function Button(props: ButtonProps) {
    const variant = props.variant ?? "primary";
    const size = props.size ?? "md";
    const classes = cn("btn", `btn--${variant}`, `btn--${size}`, props.className);

    const inner = (
        <>
            {props.children}
            <span className="btn__sheen" aria-hidden="true" />
        </>
    );

    if (props.href !== undefined) {
        return (
            <a
                href={props.href}
                className={classes}
                {...restOf(props as unknown as Record<string, unknown>)}
            >
                {inner}
            </a>
        );
    }

    return (
        <button className={classes} {...restOf(props as unknown as Record<string, unknown>)}>
            {inner}
        </button>
    );
}
