import { useEffect, useRef, useState } from "react";

interface UseInViewOptions {
    rootMargin?: string;
    threshold?: number;
    once?: boolean;
}

export function useInView<T extends HTMLElement = HTMLElement>(options: UseInViewOptions = {}) {
    const { rootMargin = "0px 0px -12% 0px", threshold = 0, once = true } = options;
    const ref = useRef<T | null>(null);
    const [inView, setInView] = useState(false);

    useEffect(() => {
        const el = ref.current;
        if (!el) return;
        if (typeof IntersectionObserver === "undefined") {
            setInView(true);
            return;
        }
        const observer = new IntersectionObserver(
            (entries) => {
                const entry = entries[0];
                if (!entry) return;
                if (entry.isIntersecting) {
                    setInView(true);
                    if (once) observer.unobserve(entry.target);
                } else if (!once) {
                    setInView(false);
                }
            },
            { rootMargin, threshold },
        );
        observer.observe(el);
        return () => observer.disconnect();
    }, [rootMargin, threshold, once]);

    return { ref, inView };
}
