import { cn } from "../../lib/cn";

interface MediaSlotProps {
    src: string;
    poster?: string;
    alt?: string;
    className?: string;
}

function isVideo(src: string): boolean {
    return /\.(webm|mp4|mov|m4v)$/i.test(src);
}

/**
 * Boxed media frame. Renders a muted, looping, autoplaying video or a
 * lazily-loaded image. Used once a real asset exists in the registry.
 */
export function MediaSlot({ src, poster, alt, className }: MediaSlotProps) {
    const video = isVideo(src);

    return (
        <div className={cn("media-slot", className)}>
            {video ? (
                <video
                    src={src}
                    poster={poster}
                    muted
                    loop
                    autoPlay
                    playsInline
                    preload="none"
                    aria-hidden={!alt}
                />
            ) : (
                <img src={src} alt={alt ?? ""} loading="lazy" decoding="async" />
            )}
        </div>
    );
}
