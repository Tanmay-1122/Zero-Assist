import type { ReactNode } from "react";
import { getAsset, type AssetKey } from "../../lib/assets";
import { cn } from "../../lib/cn";
import { MediaSlot } from "./MediaSlot";
import { VisualPlaceholder } from "./VisualPlaceholder";

interface AssetFrameProps {
    /** Key into the ASSETS registry (src/lib/assets.ts). */
    slot: AssetKey;
    className?: string;
    /** Procedural art shown until the real asset is ready. */
    children?: ReactNode;
    /**
     * When false, renders full-bleed (no box) — for backgrounds.
     * Defaults to true (boxed media frame).
     */
    frame?: boolean;
}

/**
 * The drop-in point for future assets. Reads the registry; renders real
 * media when `ready`, otherwise shows the labelled placeholder children.
 */
export function AssetFrame({ slot, className, children, frame = true }: AssetFrameProps) {
    const asset = getAsset(slot);

    if (asset.ready) {
        if (frame) {
            return (
                <MediaSlot
                    src={asset.src}
                    poster={asset.poster}
                    alt={asset.alt}
                    className={className}
                />
            );
        }
        return (
            <div className={cn("asset-frame", className)}>
                <MediaSlot src={asset.src} poster={asset.poster} alt={asset.alt} />
            </div>
        );
    }

    if (!frame) {
        return <div className={cn("asset-frame", className)}>{children}</div>;
    }

    return (
        <div className={cn("media-slot", className)}>
            <VisualPlaceholder label={asset.label}>{children}</VisualPlaceholder>
        </div>
    );
}
