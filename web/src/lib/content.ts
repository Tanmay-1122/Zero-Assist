/**
 * Single source of truth for all website copy.
 * Swap copy here without touching section components.
 */
export const site = {
    brand: "HHGOA",
    product: "Zero-Assist",
    engine: "ZeroClaw",
    repoUrl: "https://github.com/Tanmay-1122/Zero-Assist",
    docsUrl: "https://github.com/Tanmay-1122/Zero-Assist#readme",
    licenseUrl: "https://github.com/Tanmay-1122/Zero-Assist/blob/main/LICENSE",
    noticeUrl: "https://github.com/Tanmay-1122/Zero-Assist/blob/main/NOTICE.md",
    contributingUrl: "https://github.com/Tanmay-1122/Zero-Assist/blob/main/CONTRIBUTING.md",
    tagline: "On-device intelligence",
} as const;

export interface NavLink {
    label: string;
    href: string;
}

export const navLinks: NavLink[] = [
    { label: "Manifesto", href: "#manifesto" },
    { label: "Capabilities", href: "#capabilities" },
    { label: "Engine", href: "#engine" },
    { label: "Privacy", href: "#privacy" },
    { label: "Story", href: "#story" },
];

export const marqueeItems = [
    "On-device voice",
    "Terminal REPL",
    "Linux sandbox",
    "Device automation",
    "25+ channels",
    "Local models",
    "Encrypted memory",
    "Vision & barcode",
    "Wake-word ready",
    "Emergency stop",
];

export const hero = {
    kicker: "On-device intelligence",
    titleBefore: "Intelligence that stays",
    titleAfter: "in your",
    titleAccent: "pocket.",
    sub: `${site.product} is an open-source, on-device AI assistant for Android — powered by the ${site.engine} Rust engine. Voice, vision, automation, and a real Linux sandbox, running entirely where they belong: on your device.`,
    primaryCta: "Get the app",
    ghostCta: "Read the docs",
    meta: ["Android 8.0+", "Open source · MIT", "4 native ABIs", "No cloud account"],
};

export const manifesto = {
    kicker: "Manifesto",
    statement:
        "The cloud was a detour. Real privacy is physical — intelligence that lives in your pocket and answers to no server.",
    accentWords: ["physical", "pocket", "server"],
    foot: "Zero-Assist inverts the model. Instead of shipping your life to a data center, it brings intelligence home — and lets you keep the keys.",
    signature: "— HHGOA",
};

export interface Capability {
    id: string;
    index: string;
    title: string;
    desc: string;
    slot: string;
    tone: "default" | "gold" | "warm";
}

export const capabilities: Capability[] = [
    {
        id: "voice",
        index: "01",
        title: "Voice",
        desc: "Wake-word ready. On-device speech recognition and Piper TTS with per-app voice profiles — a hands-free assistant that answers from your pocket.",
        slot: "cap-voice",
        tone: "default",
    },
    {
        id: "terminal",
        index: "02",
        title: "Terminal",
        desc: "A streaming REPL backed by the Rust engine. Canvas-style markdown rendering, live tool calls, and multi-agent group chat.",
        slot: "cap-terminal",
        tone: "gold",
    },
    {
        id: "device-control",
        index: "03",
        title: "Device control",
        desc: "The agent sees your screen and acts — tap, type, swipe, launch. A model-backed planner that verifies results and recovers from failures.",
        slot: "cap-device",
        tone: "warm",
    },
    {
        id: "sandbox",
        index: "04",
        title: "Linux sandbox",
        desc: "A PRoot-based Linux rootfs on your phone. Real tooling, real shells, orchestrated by the agent through a sidecar bridge.",
        slot: "cap-sandbox",
        tone: "default",
    },
    {
        id: "channels",
        index: "05",
        title: "Channels",
        desc: "Telegram, Discord, WhatsApp, Signal, email, IRC and 25+ more. The same agent, everywhere you already talk.",
        slot: "cap-channels",
        tone: "gold",
    },
    {
        id: "memory",
        index: "06",
        title: "Memory",
        desc: "Local embeddings and encrypted storage. The assistant remembers what you allow — on your device, on your terms.",
        slot: "cap-memory",
        tone: "warm",
    },
];

export const engine = {
    kicker: "The engine",
    title: "A Rust engine, sized for your pocket.",
    copy: `${site.product} is built around ${site.engine} — a feature-gated Rust agent compiled for size and speed. A tiny native core does the heavy lifting, bound to Android through generated UniFFI and JNA bridges.`,
    stats: [
        { value: "14", label: "Workspace crates" },
        { value: "2024", label: "Rust edition" },
        { value: "z+LTO", label: "Size-optimized" },
        { value: "4", label: "Android ABIs" },
    ],
    railLabel: "How the stack stacks",
    nodes: [
        {
            name: "Compose UI",
            tag: "Kotlin · M3",
            desc: "Adaptive screens — terminal, voice, dashboard, skills, channels, settings.",
        },
        {
            name: "JNA + UniFFI",
            tag: "Generated bindings",
            desc: "A thin FFI layer that exposes the engine to Android without reimplementing it.",
        },
        {
            name: "ZeroClaw engine",
            tag: "Rust · 2024",
            desc: "Runtime, tools, memory, channels, plugins, observability — one native core.",
        },
        {
            name: "On-device services",
            tag: "Android",
            desc: "Accessibility, Termux bridge, PRoot sandbox, daemon, quick-settings tile.",
        },
    ],
};

export const privacy = {
    kicker: "Privacy",
    title: "Your phone is",
    accent: "the server.",
    copy: "No account. No telemetry. No cloud dependency for core functionality. Speech, models, memory, and keys stay on your device — because the device is the platform.",
    points: [
        {
            icon: "01",
            title: "Encrypted at rest",
            desc: "SQLCipher-protected database and secure preferences keep every record on-device.",
        },
        {
            icon: "02",
            title: "Screen protection",
            desc: "FLAG_SECURE shields sensitive UI from capture in release builds.",
        },
        {
            icon: "03",
            title: "Local speech",
            desc: "Recognition and synthesis run on-device — no audio ever leaves your phone.",
        },
        {
            icon: "04",
            title: "Your keys, your vault",
            desc: "API keys live in an encrypted vault, never in cloud logs.",
        },
    ],
};

export interface StoryFrame {
    id: string;
    tag: string;
    title: string;
    accent: string;
    desc: string;
    slot: string;
    label: string;
}

export const storyFrames: StoryFrame[] = [
    {
        id: "voice",
        tag: "Chapter I — Voice",
        title: "You",
        accent: "speak.",
        desc: "A wake-word assistant answers hands-free — on-device recognition and Piper TTS, with per-app voice profiles.",
        slot: "story-voice",
        label: "Voice",
    },
    {
        id: "act",
        tag: "Chapter II — Automation",
        title: "You",
        accent: "ask.",
        desc: "A UI agent observes your screen and acts — tap, type, swipe, launch. Real device automation, guarded by safety policies.",
        slot: "story-act",
        label: "Automation",
    },
    {
        id: "memory",
        tag: "Chapter III — Memory",
        title: "You",
        accent: "remember.",
        desc: "Local embeddings and encrypted memory keep context where it belongs — in your pocket, not in a server.",
        slot: "story-memory",
        label: "Memory",
    },
];

export const openSource = {
    kicker: "Open source",
    title: "Built in the open. Under your control.",
    copy: `${site.product} is MIT-licensed, built in the open, and runnable from source. Audit it, fork it, shape it — the code is the documentation.`,
    primaryCta: "Get it on GitHub",
    ghostCta: "Contributing guide",
    badges: ["MIT License", "Kotlin 2.0", "Rust 2024", "Android 8.0+"],
    release: {
        name: site.product,
        status: "Open build",
        meta: "Android 8.0+ · API 26–35 · Split APKs",
        abis: [
            { arch: "arm64-v8a", role: "Primary", primary: true },
            { arch: "armeabi-v7a", role: "Compat", primary: false },
            { arch: "x86_64", role: "Emulator", primary: false },
            { arch: "x86", role: "Compat", primary: false },
        ],
        action: "Download from GitHub",
        note: "APKs are published with each release",
    },
};

export const cta = {
    titleBefore: "Take your intelligence",
    accent: "with you.",
    primaryCta: "Get the app",
    ghostCta: "Explore the code",
    note: "MIT licensed · open source · no account required",
    watermark: "HHGOA",
};

export const footer = {
    blurb: `${site.brand} builds on-device intelligence. ${site.product} is open source under MIT, powered by the ${site.engine} Rust engine.`,
    columns: [
        {
            heading: "Product",
            links: [
                { label: "Manifesto", href: "#manifesto" },
                { label: "Capabilities", href: "#capabilities" },
                { label: "Engine", href: "#engine" },
                { label: "Privacy", href: "#privacy" },
            ],
        },
        {
            heading: "Developers",
            links: [
                { label: "GitHub", href: site.repoUrl },
                { label: "Documentation", href: site.docsUrl },
                { label: "Contributing", href: site.contributingUrl },
            ],
        },
        {
            heading: "Legal",
            links: [
                { label: "MIT License", href: site.licenseUrl },
                { label: "NOTICE", href: site.noticeUrl },
            ],
        },
    ],
    bottomLeft: "© 2026 HHGOA",
    bottomRight: "Built with the ZeroClaw engine",
};
