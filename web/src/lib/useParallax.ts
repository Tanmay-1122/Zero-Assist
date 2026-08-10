import { useEffect, useRef } from "react";
import { usePrefersReducedMotion } from "./usePrefersReducedMotion";

/**
 * Subtle scroll parallax. Sets `--parallax-y` on the element so the
 * CSS `.parallax` class can translate it. Decorative use only.
 */
export function useParallax<T extends HTMLElement = HTMLDivElement>(speed = 0.08) {
    const ref = useRef<T | null>(null);
    const reduced = usePrefersReducedMotion();

    useEffect(() => {
        const el = ref.current;
        if (!el || reduced) return;

        let raf = 0;
        const update = () => {
            cancelAnimationFrame(raf);
            raf = requestAnimationFrame(() => {
                const rect = el.getBoundingClientRect();
                const center = rect.top + rect.height / 2 - window.innerHeight / 2;
                el.style.setProperty("--parallax-y", `${(center * speed).toFixed(1)}px`);
            });
        };

        update();
        window.addEventListener("scroll", update, { passive: true });
        window.addEventListener("resize", update);
        return () => {
            cancelAnimationFrame(raf);
            window.removeEventListener("scroll", update);
            window.removeEventListener("resize", update);
        };
    }, [speed, reduced]);

    return ref;
}
