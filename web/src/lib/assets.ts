/**
 * Asset registry — the single integration point for future media.
 *
 * When a generated asset is added to `public/assets/...`, set
 * `ready: true` (and adjust `src`/`poster` if needed). Every
 * `AssetFrame` slot on the site will render the real media
 * automatically — no layout changes required.
 *
 * See ASSETS_NEEDED.md for the full catalogue.
 */

export type AssetPriority = "hero" | "high" | "medium" | "low";

export interface AssetSlot {
    /** Label shown inside the placeholder until the asset is ready. */
    label: string;
    /** Real asset path (webm/mp4 → video, otherwise image). */
    src: string;
    /** Optional poster frame for video assets. */
    poster?: string;
    /** Accessible name for the final media. */
    alt: string;
    /** Flip to true once the file exists at `src`. */
    ready: boolean;
    priority: AssetPriority;
}

export const ASSETS = {
    "hero-keyart": {
        label: "Hero key art",
        src: "/assets/hero/hero-keyart.webm",
        poster: "/assets/hero/hero-keyart-poster.webp",
        alt: "Animated HHGOA hero key art — the product's signature visual.",
        ready: false,
        priority: "hero",
    },
    "cap-voice": {
        label: "Capability — voice",
        src: "/assets/illustrations/capability-voice.webp",
        alt: "On-device voice recognition illustration.",
        ready: false,
        priority: "high",
    },
    "cap-terminal": {
        label: "Capability — terminal",
        src: "/assets/illustrations/capability-terminal.webp",
        alt: "Streaming terminal REPL illustration.",
        ready: false,
        priority: "high",
    },
    "cap-device": {
        label: "Capability — device control",
        src: "/assets/illustrations/capability-device.webp",
        alt: "UI agent automating the device screen illustration.",
        ready: false,
        priority: "high",
    },
    "cap-sandbox": {
        label: "Capability — Linux sandbox",
        src: "/assets/illustrations/capability-sandbox.webp",
        alt: "On-device Linux sandbox illustration.",
        ready: false,
        priority: "high",
    },
    "cap-channels": {
        label: "Capability — channels",
        src: "/assets/illustrations/capability-channels.webp",
        alt: "Messaging channels connected to the agent illustration.",
        ready: false,
        priority: "medium",
    },
    "cap-memory": {
        label: "Capability — memory",
        src: "/assets/illustrations/capability-memory.webp",
        alt: "Local encrypted memory illustration.",
        ready: false,
        priority: "medium",
    },
    "privacy-vault": {
        label: "Privacy vault artwork",
        src: "/assets/illustrations/privacy-vault.webp",
        alt: "On-device encrypted vault artwork.",
        ready: false,
        priority: "high",
    },
    "story-voice": {
        label: "Story — voice frame",
        src: "/assets/illustrations/story-voice.webp",
        alt: "Voice chapter illustration.",
        ready: false,
        priority: "medium",
    },
    "story-act": {
        label: "Story — automation frame",
        src: "/assets/illustrations/story-act.webp",
        alt: "Automation chapter illustration.",
        ready: false,
        priority: "medium",
    },
    "story-memory": {
        label: "Story — memory frame",
        src: "/assets/illustrations/story-memory.webp",
        alt: "Memory chapter illustration.",
        ready: false,
        priority: "medium",
    },
} as const satisfies Record<string, AssetSlot>;

export type AssetKey = keyof typeof ASSETS;

export function getAsset(key: AssetKey): AssetSlot {
    return ASSETS[key];
}
