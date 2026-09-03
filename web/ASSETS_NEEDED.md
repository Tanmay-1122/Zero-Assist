# ASSETS_NEEDED.md

Catalogue of every visual asset the HHGOA website is designed to accept.

**How integration works:** every slot in this file maps to a key in
`src/lib/assets.ts` (the registry). When an asset file is dropped into its
`Destination path`, flip `ready: true` for that key — the site renders the real
media in the exact same frame, with zero layout changes.

All assets live under `public/assets/` so they are served at `/assets/...`.
Empty subfolders are kept with `.gitkeep`.

**Final formats — fast reference**

| Kind          | Preferred format                            | Size guidance            |
| ------------- | ------------------------------------------- | ------------------------ |
| Hero          | WebM (VP9) + WebP poster                    | ≤ 6 MB, 1080p/1440p      |
| Illustrations | WebP (transparent)                          | 512–2048 px longest side |
| Motion        | WebM (VP9) / Lottie JSON / Rive (riv)       | ≤ 3 MB                   |
| Backgrounds   | WebP (compressed, low contrast)             | 1440–2560 px wide        |
| 3D            | GLB (Draco-compressed) for `<model-viewer>` | ≤ 4 MB                   |

---

## 1. Hero key art — `hero-keyart`

- **Name:** Hero key art
- **Purpose:** Signature cinematic visual behind the hero headline; communicates "a mind that lives on your phone" without clichés.
- **Location:** Hero background (`src/sections/Hero.tsx` → `AssetFrame slot="hero-keyart"`).
- **Suggested format:** Animated WebM (VP9), fallback poster WebP.
- **Recommended dimensions:** 1920 × 1080 (loop-friendly); poster 1920 × 1080.
- **Aspect ratio:** 16:9, safe for 100svh crop behind text.
- **Transparent background:** No — full-bleed background. Text sits on top at 0.55 opacity.
- **Mobile version needed:** Yes — a vertical 1080 × 1920 cut or a composition that reads centered on small screens.
- **Animation needed:** Yes — gentle, slow, continuous loop (8–20 s). No flashing.
- **Visual description:** An abstract, depth-of-field composition in HHGOA palette (deep navy #0E1525 → #17355F, teal #1EA7A3, gold #C9A227, warm #FF8A3D). Soft volumetric light fields, a slow-rotating set of concentric light rings, and a small luminous core — elegant, calm, luxurious. Avoid: robots, brains, circuits, hexagons, holograms, neon overload. Matte, cinematic grade; grain already supplied by the site.
- **Suggested AI prompt:** "Cinematic abstract key art, dark deep-navy background, soft volumetric teal and gold light, slow-rotating concentric luminous rings around a small radiant teal core, elegant luxurious calm atmosphere, matte film grade, 16:9, subtle depth of field, no text, no logos, no robots, no circuit patterns."
- **Destination path:** `public/assets/hero/hero-keyart.webm` + `hero-keyart-poster.webp`
- **Priority:** Hero — highest.

---

## 2. Capability illustrations — `cap-voice` … `cap-memory`

Six small editorial illustrations revealed when the user hovers/focuses a
capability row in the "Capabilities" dossier (`src/sections/Capabilities.tsx`).

Each thumbnail is displayed at ~11rem tall × 24rem wide (384 × 176 CSS px),
inside a rounded, masked frame.

### 2a. Voice — `cap-voice`

- **Purpose:** On-device speech recognition & synthesis.
- **Format:** WebP (transparent) or a subtle animated WebM.
- **Dimensions:** 768 × 352.
- **Aspect ratio:** 2.18 : 1.
- **Transparent:** Yes (painted over the existing gradient thumb, which stays).
- **Animation:** Optional slow shimmer.
- **Visual description:** Abstract sound-wave ribbons and a small glowing orb, teal-dominant. Editorial, flat-layered, tasteful.
- **AI prompt:** "Abstract editorial illustration of a voice wave dissolving into soft glowing teal particles, flat layered composition, dark navy background, teal and warm gold accents, premium minimal style."
- **Destination path:** `public/assets/illustrations/capability-voice.webp`
- **Priority:** High.

### 2b. Terminal — `cap-terminal`

- **Purpose:** Streaming terminal REPL.
- **Format:** WebP.
- **Dimensions / ratio:** 768 × 352 · 2.18 : 1.
- **Transparent:** Yes.
- **Animation:** Optional cursor blink in WebM.
- **Visual description:** Abstract typing/streaming motif — rows of soft horizontal light lines that taper like a queue, gold accent.
- **AI prompt:** "Abstract editorial illustration of parallel light streams cascading down like a terminal log, flat layered, gold on deep navy, premium minimal."
- **Destination path:** `public/assets/illustrations/capability-terminal.webp`
- **Priority:** High.

### 2c. Device control — `cap-device`

- **Purpose:** Accessibility-based UI automation.
- **Format:** WebP.
- **Dimensions / ratio:** 768 × 352 · 2.18 : 1.
- **Transparent:** Yes.
- **Animation:** Optional.
- **Visual description:** A minimal abstract phone outline with a fingertip-like teal arc acting on a floating card. Avoid hand renderings — keep abstract.
- **AI prompt:** "Abstract editorial illustration, minimal rounded phone silhouette with a glowing teal arc gesture touching a floating translucent card, flat layers, warm orange accent, premium minimal."
- **Destination path:** `public/assets/illustrations/capability-device.webp`
- **Priority:** High.

### 2d. Linux sandbox — `cap-sandbox`

- **Purpose:** PRoot Linux rootfs on-device.
- **Format:** WebP.
- **Dimensions / ratio:** 768 × 352 · 2.18 : 1.
- **Transparent:** Yes.
- **Visual description:** Layered nested translucent panels (a box within a box) suggesting a sandbox, teal + surface-blue palette.
- **AI prompt:** "Abstract editorial illustration of nested translucent rounded containers suggesting an isolated sandbox, teal and deep navy, soft inner glow, premium minimal."
- **Destination path:** `public/assets/illustrations/capability-sandbox.webp`
- **Priority:** High.

### 2e. Channels — `cap-channels`

- **Purpose:** 25+ messaging channels.
- **Format:** WebP.
- **Dimensions / ratio:** 768 × 352 · 2.18 : 1.
- **Transparent:** Yes.
- **Visual description:** An abstract radial fan of thin golden lines from a central node — suggesting broadcast, not circuit boards.
- **AI prompt:** "Abstract editorial illustration of a central luminous point with thin radiating gold lines like a subtle fan of connections, flat layered, deep navy, premium minimal."
- **Destination path:** `public/assets/illustrations/capability-channels.webp`
- **Priority:** Medium.

### 2f. Memory — `cap-memory`

- **Purpose:** Local embeddings & encrypted memory.
- **Format:** WebP.
- **Dimensions / ratio:** 768 × 352 · 2.18 : 1.
- **Transparent:** Yes.
- **Visual description:** Concentric memory rings / a compact spiral lock motif in warm orange, echoing the site's ring language.
- **AI prompt:** "Abstract editorial illustration of concentric memory rings closing around a small warm-orange core, flat layered, deep navy, premium minimal, secure calm mood."
- **Destination path:** `public/assets/illustrations/capability-memory.webp`
- **Priority:** Medium.

---

## 3. Privacy vault — `privacy-vault`

- **Name:** Privacy vault artwork
- **Purpose:** Visual for the "Your phone is the server" section — replaces the procedural vault (`src/sections/Privacy.tsx`).
- **Format:** WebP (transparent) or short WebM loop.
- **Dimensions:** 1080 × 1080.
- **Aspect ratio:** 1 : 1.
- **Transparent:** Yes.
- **Mobile version:** Yes — scales down, keep centered.
- **Animation:** Optional — slow orbiting highlight.
- **Visual description:** An elegant, concentric square-within-square vault of layered translucent panels around a small sealed teal core — the visual "seal" of the product. Luxurious, quiet, golden hairline accents.
- **AI prompt:** "Abstract editorial vault illustration, concentric rounded translucent layers sealing a small radiant teal core, thin gold hairlines, deep navy background, premium minimal luxurious, 1:1."
- **Destination path:** `public/assets/illustrations/privacy-vault.webp`
- **Priority:** High.

---

## 4. Story chapter frames — `story-voice`, `story-act`, `story-memory`

Three frames in the sticky scroll "Story" section (`src/sections/Story.tsx`),
shown at ~30rem wide × 11rem tall (480 × 176 CSS px).

### 4a. Voice — `story-voice`

- **Purpose:** Chapter I — hands-free voice.
- **Format:** WebP.
- **Dimensions / ratio:** 960 × 352 · 2.7 : 1.
- **Transparent:** Yes.
- **Visual:** Soft teal sound-halo ring.
- **Destination path:** `public/assets/illustrations/story-voice.webp` · **Priority:** Medium.

### 4b. Automation — `story-act`

- **Purpose:** Chapter II — UI agent acting.
- **Format:** WebP.
- **Dimensions / ratio:** 960 × 352 · 2.7 : 1.
- **Transparent:** Yes.
- **Visual:** Minimal phone silhouette with a teal action arc.
- **Destination path:** `public/assets/illustrations/story-act.webp` · **Priority:** Medium.

### 4c. Memory — `story-memory`

- **Purpose:** Chapter III — local memory.
- **Format:** WebP.
- **Dimensions / ratio:** 960 × 352 · 2.7 : 1.
- **Transparent:** Yes.
- **Visual:** Warm-orange ring spiral.
- **Destination path:** `public/assets/illustrations/story-memory.webp` · **Priority:** Medium.

---

## 5. Optional background textures — `backgrounds/`

- **Purpose:** To enrich sections (Hero aurora, Privacy, CTA) once available.
- **Formats:** WebP (seamless tile) or a single 2560 × 1440 piece. Low contrast, dark, blur-friendly.
- **Transparent:** No.
- **Animation:** Optional slow WebM pan/zoom loop.
- **Examples to generate:**
    - `textures/grain.webp` — supersedes the inline SVG grain (optional).
    - `backgrounds/aurora-1.webp`, `backgrounds/aurora-2.webp` — volumetric light fields in palette.
- **Destination paths:** `public/assets/backgrounds/`, `public/assets/textures/`
- **Priority:** Low.

---

## 6. Video & animation folder — `videos/`, `animations/`

- **Purpose:** Reserved for future editorial clips (product walkthrough, engine visualization) and Lottie/Rive micro-motion.
- **Formats:** WebM (VP9), Lottie `.json`, Rive `.riv`.
- **Destinations:** `public/assets/videos/`, `public/assets/animations/`
- **Priority:** Low.

---

## 7. 3D — `3d/`

- **Purpose:** Reserved for a future WebGL / Three.js / Spline / `<model-viewer>` product object (e.g., the ring monogram as a 3D object).
- **Format:** GLB, Draco-compressed.
- **Destination:** `public/assets/3d/`
- **Priority:** Low.

---

## Adding an asset — step by step

1. Generate the asset per the spec above.
2. Save it to its destination path (e.g. `public/assets/hero/hero-keyart.webm`).
3. Open `src/lib/assets.ts` and set `ready: true` for that key (and confirm `src`/`poster`/`alt`).
4. Run `npm run dev` or `npm run build` — the frame, layout, and behaviour are already built.

No component changes are needed. The placeholder label ("Todo · …") disappears
automatically once `ready` is true.
