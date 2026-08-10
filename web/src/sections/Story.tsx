import { useEffect, useRef, useState } from "react";
import { storyFrames } from "../lib/content";
import { cn } from "../lib/cn";
import { usePrefersReducedMotion } from "../lib/usePrefersReducedMotion";
import { AssetFrame } from "../components/media/AssetFrame";

/**
 * Sticky scroll storytelling. A pinned stage crossfades between
 * chapters as the reader scrolls. Respects reduced motion.
 */
export function Story() {
    const [active, setActive] = useState(0);
    const reduced = usePrefersReducedMotion();
    const triggerRefs = useRef<(HTMLDivElement | null)[]>([]);

    useEffect(() => {
        const observers = storyFrames.map((_, i) => {
            const el = triggerRefs.current[i];
            if (!el) return null;
            const observer = new IntersectionObserver(
                (entries) => {
                    if (entries[0]?.isIntersecting) setActive(i);
                },
                { rootMargin: "-45% 0px -45% 0px", threshold: 0 },
            );
            observer.observe(el);
            return observer;
        });
        return () => observers.forEach((o) => o?.disconnect());
    }, []);

    const goTo = (i: number) => {
        triggerRefs.current[i]?.scrollIntoView({ behavior: "smooth", block: "start" });
    };

    return (
        <section className="section story" id="story" aria-label="The assistant's story">
            <div className="story__stage">
                {storyFrames.map((frame, i) => (
                    <div
                        key={frame.id}
                        className={cn("story__frame", i === active && "story__frame--active")}
                        role="group"
                        aria-hidden={!reduced && i !== active}
                        aria-labelledby={`story-title-${frame.id}`}
                    >
                        <p className="story__frame-tag">{frame.tag}</p>
                        <h2 className="story__frame-title" id={`story-title-${frame.id}`}>
                            {frame.title}{" "}
                            {frame.accent && <em className="grad-text">{frame.accent}</em>}
                        </h2>
                        <p className="story__frame-desc">{frame.desc}</p>
                        {/* TODO: Replace with generated chapter artwork */}
                        <AssetFrame
                            slot={frame.slot as import("../lib/assets").AssetKey}
                            className="story__frame-media"
                        >
                            <div className="cap-thumb" />
                        </AssetFrame>
                    </div>
                ))}

                <div className="story__progress" role="tablist" aria-label="Story chapters">
                    {storyFrames.map((frame, i) => (
                        <button
                            key={frame.id}
                            type="button"
                            className={cn("story__dot", i === active && "story__dot--active")}
                            aria-label={frame.label}
                            aria-selected={i === active}
                            role="tab"
                            onClick={() => goTo(i)}
                        />
                    ))}
                </div>
            </div>

            {/* Scroll triggers — one screen per chapter */}
            {storyFrames.map((frame, i) => (
                <div
                    key={`${frame.id}-trigger`}
                    ref={(el) => {
                        triggerRefs.current[i] = el;
                    }}
                    className="story__trigger"
                    aria-hidden="true"
                />
            ))}
        </section>
    );
}
