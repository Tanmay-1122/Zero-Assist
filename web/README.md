# HHGOA — Website

Premium marketing site for **HHGOA / Zero-Assist** — an open-source, on-device
AI assistant for Android powered by the ZeroClaw Rust engine.

Built as a fresh, self-contained frontend inside the Android repo. No legacy
website was used as a reference.

## Stack

- **Vite 6** + **React 18** + **TypeScript** (strict)
- **No animation libraries** — motion is hand-rolled (IntersectionObserver +
  CSS transforms) to keep the bundle tiny
- Fonts: **Fraunces** (editorial display serif) + **Manrope** (sans), via Google
  Fonts with system fallbacks

## Getting started

```bash
cd web
npm install
npm run dev        # local dev server
npm run build      # typecheck + production build
npm run preview    # serve the production build
npm run lint       # eslint
npm run format     # prettier
```

## Structure

```
web/
├── index.html
├── src/
│   ├── styles/
│   │   ├── tokens.css      # design tokens (color, type, space, motion, layout)
│   │   ├── base.css        # reset, focus, reduced-motion, grain
│   │   ├── components.css  # buttons, nav, footer, marquee, media/placeholders
│   │   └── sections.css    # per-section compositions
│   ├── lib/
│   │   ├── content.ts      # all site copy — edit here, not in components
│   │   ├── assets.ts       # ASSET REGISTRY — the drop-in point for media
│   │   └── hooks/          # useInView, useParallax, usePrefersReducedMotion
│   ├── components/
│   │   ├── ui/             # Button, Container, Kicker, Wordmark, Marquee, Grain
│   │   ├── motion/         # Reveal, WordMask, Parallax
│   │   ├── media/          # AssetFrame, MediaSlot, VisualPlaceholder
│   │   └── layout/         # Nav, Footer
│   └── sections/           # Hero, Manifesto, Capabilities, Engine, Privacy,
│                           #   Story, OpenSource, CTA
├── public/assets/          # future media: hero/, illustrations/, textures/, …
└── ASSETS_NEEDED.md        # full asset catalogue with generation prompts
```

## Design system

Everything is driven by CSS custom properties in `src/styles/tokens.css`:

- Palette per spec: bg `#05070A`, surface `#0E1525`, secondary `#17355F`,
  accent `#1EA7A3`, highlight `#C9A227`, warm `#FF8A3D`, text `#F8F9FA`
- Fluid type scale (clamp-based) for editorial hierarchy
- 4px spacing scale, radii, shadows/glows, easing curves, section rhythm

## Motion language

- **Reveal** — fade + rise on scroll, optional stagger delay
- **WordMask** — editorial word-by-word rise (manifesto)
- **Parallax** — subtle layer drift (decorative only)
- **Marquee** — slow capability ticker
- **Story** — sticky pinned stage that crossfades chapters on scroll
- Everything respects `prefers-reduced-motion`; no scroll-jacking

## Adding real assets

1. Generate per `ASSETS_NEEDED.md`.
2. Save to `public/assets/<kind>/…`.
3. In `src/lib/assets.ts`, set `ready: true` for the matching key.

The `AssetFrame` component (`src/components/media/AssetFrame.tsx`) then renders
the real media in the existing frame — no layout changes. Placeholder labels
disappear automatically.
