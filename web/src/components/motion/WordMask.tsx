import type { CSSProperties } from "react";
import { useInView } from "../../lib/useInView";
import { cn } from "../../lib/cn";

interface WordMaskProps {
    text: string;
    /** Words rendered as italic gradient accents. */
    accentWords?: readonly string[];
    /** Delay before the first word rises, in ms. */
    startDelay?: number;
    /** Delay added per word, in ms. */
    stepDelay?: number;
    className?: string;
}

/**
 * Editorial word-by-word reveal. Each word slides up out of a mask
 * as the block enters the viewport.
 */
export function WordMask({
    text,
    accentWords = [],
    startDelay = 0,
    stepDelay = 90,
    className,
}: WordMaskProps) {
    const { ref, inView } = useInView<HTMLDivElement>();
    const words = text.split(" ");
    const accent = new Set(accentWords);

    return (
        <div ref={ref} className={cn(className)}>
            {words.map((word, i) => {
                const style = {
                    "--mask-delay": `${startDelay + i * stepDelay}ms`,
                } as CSSProperties;
                const isAccent = accent.has(word.replace(/[.,;:!?—–-]/g, ""));
                return (
                    <span
                        key={`${word}-${i}`}
                        className={cn("mask", inView && "mask--in")}
                        style={style}
                    >
                        <span className={cn("mask__inner", isAccent && "grad-text")}>
                            {isAccent ? <em>{word}</em> : word}
                            {i < words.length - 1 ? "\u00A0" : ""}
                        </span>
                    </span>
                );
            })}
        </div>
    );
}
