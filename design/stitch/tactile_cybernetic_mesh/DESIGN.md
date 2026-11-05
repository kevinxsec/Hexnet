---
name: Tactile Cybernetic Mesh
colors:
  surface: '#131313'
  surface-dim: '#131313'
  surface-bright: '#393939'
  surface-container-lowest: '#0e0e0e'
  surface-container-low: '#1b1b1b'
  surface-container: '#1f1f1f'
  surface-container-high: '#2a2a2a'
  surface-container-highest: '#353535'
  on-surface: '#e2e2e2'
  on-surface-variant: '#c6c5d5'
  inverse-surface: '#e2e2e2'
  inverse-on-surface: '#303030'
  outline: '#908f9e'
  outline-variant: '#454652'
  surface-tint: '#bcc2ff'
  primary: '#bcc2ff'
  on-primary: '#0f208b'
  primary-container: '#8795ff'
  on-primary-container: '#16268f'
  inverse-primary: '#4654ba'
  secondary: '#cdbdff'
  on-secondary: '#370294'
  secondary-container: '#502ead'
  on-secondary-container: '#c0acff'
  tertiary: '#ccbeff'
  on-tertiary: '#342171'
  tertiary-container: '#a391e7'
  on-tertiary-container: '#382676'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#dfe0ff'
  primary-fixed-dim: '#bcc2ff'
  on-primary-fixed: '#000c61'
  on-primary-fixed-variant: '#2d3ba1'
  secondary-fixed: '#e8deff'
  secondary-fixed-dim: '#cdbdff'
  on-secondary-fixed: '#20005f'
  on-secondary-fixed-variant: '#4e2baa'
  tertiary-fixed: '#e7deff'
  tertiary-fixed-dim: '#ccbeff'
  on-tertiary-fixed: '#1e035c'
  on-tertiary-fixed-variant: '#4a3989'
  background: '#131313'
  on-background: '#e2e2e2'
  surface-variant: '#353535'
typography:
  display-lg:
    fontFamily: JetBrains Mono
    fontSize: 34px
    fontWeight: '700'
    lineHeight: 42px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: JetBrains Mono
    fontSize: 27px
    fontWeight: '700'
    lineHeight: 35px
    letterSpacing: -0.015em
  headline-lg-mobile:
    fontFamily: JetBrains Mono
    fontSize: 24px
    fontWeight: '700'
    lineHeight: 32px
    letterSpacing: -0.015em
  headline-md:
    fontFamily: JetBrains Mono
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.01em
  title-lg:
    fontFamily: JetBrains Mono
    fontSize: 19px
    fontWeight: '600'
    lineHeight: 26px
    letterSpacing: 0em
  title-md:
    fontFamily: JetBrains Mono
    fontSize: 15px
    fontWeight: '500'
    lineHeight: 21px
    letterSpacing: 0em
  body-lg:
    fontFamily: JetBrains Mono
    fontSize: 15px
    fontWeight: '400'
    lineHeight: 22px
    letterSpacing: 0em
  body-md:
    fontFamily: JetBrains Mono
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: 0em
  body-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0.01em
  label-lg:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '500'
    lineHeight: 18px
    letterSpacing: 0.02em
  label-caps:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '600'
    lineHeight: 15px
    letterSpacing: 0.06em
  stat-lg:
    fontFamily: JetBrains Mono
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 38px
    letterSpacing: -0.02em
rounded:
  sm: 0.5rem
  DEFAULT: 1rem
  md: 1.5rem
  lg: 2rem
  xl: 3rem
  full: 9999px
spacing:
  gutter: 1rem
  margin: 1rem
  margin-mobile: 1rem
  margin-tablet: 1.5rem
  margin-desktop: 2rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 0.75rem
  space-lg: 1rem
  space-xl: 1.5rem
---

## Brand & Style

This design system is an unapologetic, tactical, and cybernetic communication architecture designed specifically for peer-to-peer, off-grid mesh networking. It marries the extreme power-saving benefits of true AMOLED black canvases with the luminous, optical delicacy of translucent Liquid Glass surfaces. The visual style eliminates flat grey sheets and generic corporate minimalism in favor of an illuminated heads-up display floating over deep space.

The system's interaction paradigm draws from two major ergonomic foundations:
- **Samsung One UI Ergonomics**: A decisive vertical division between the upper "viewing zone" (large expressive typographic titles, diagnostic indicators, and signal telemetry) and the lower 45% "interaction zone" (composers, action discs, filter chips, and navigation anchors within natural thumb reach).
- **Google Pixel M3 Expressive Tactility**: Tactile pill shapes, dynamic scale-down feedback (contracting to 0.94x on press with a 120ms spring snap-back), and explicit hardware haptics over standard Material ink ripples.

The interface functions strictly in dark mode, with zero light mode fallbacks. Surfaces feel crystalline, resilient, and cryptographic—evoking cold precision, total operational reliability, and field-ready cryptographic utility.

## Colors

Color within this design system operates as functional telemetry rather than passive decoration. The foundation leverages pure AMOLED black to preserve battery during critical field operations, layered with elevated dark substrates and translucent specular glass.

### Substrates & Surfaces
- **Canvas Base (`#000000`)**: Zero-emissive true black foundation across all primary viewports.
- **Obsidian Tier 1 (`#09090B`)**: Deep substrate for recessed input fields, message streams, and background lists.
- **Obsidian Tier 2 (`#101014`)**: Middle elevated surface for bottom sheets, modal trays, and floating toolbars.
- **Obsidian Tier 3 (`#17171D`)**: Highest opaque elevation tier for contextual popovers, active chips, and cryptographic key inspect cards.
- **Liquid Glass Fill (`rgba(255, 255, 255, 0.055)`)**: Translucent frosted overlay for floating sheets and structural containers.
- **Liquid Glass Hairline (`rgba(255, 255, 255, 0.12)`)**: Micro-thin 1px perimeter border providing optical definition.
- **Specular Highlight (`rgba(255, 255, 255, 0.22)`)**: Directional top-edge highlight caught by illuminated active glass components.

### Functional Accents
- **Electric Periwinkle (`#8795FF`)**: Primary action driver, high-priority interactive states, and brand glyphs.
- **Violet (`#997BFA`)**: Noise protocol security, end-to-end encryption confirmation, and verified cryptographic handshakes.
- **Soft Lavender (`#B9A7FF`)**: Mentions, interactive inline tags, and subtle focus glows.
- **Ice Blue (`#9ADCF5`)**: Geohash coordinates, spatial telemetry, and distance metrics.
- **Soft Cyan (`#84D9DB`)**: BLE mesh discovery pulses, routing hops, and live radio status.
- **Coral (`#F2A69D`)**: Packet drops, degraded mesh alerts, and local self-identity tags.
- **Mint (`#91D9BD`)**: Successful packet delivery, verified SAS confirmations, and low-latency nodes.

### Text Contrast
Primary text is set in Pure White (`#F5F5F7`), secondary telemetry in Subdued Slate (`#A1A1AA`), and muted placeholders or key hashes in Muted Steel (`#6B7280`).

## Typography

The design system enforces a single monospaced typeface across every UI surface: **JetBrains Mono**. Proportional variable-width fonts are strictly prohibited. The monospaced rhythm ensures deterministic visual columns, eliminates layout shifts during live byte or decibel telemetry updates, and reinforces a terminal-grade aesthetic.

### Layout Rules
- **Monospace Leading**: Body copy requires exaggerated line heights (minimum +6px to +7px over font size) to prevent reading fatigue common to fixed-width fonts.
- **Tabular Diagnostics**: Numerical data, coordinates, signal strengths, and cryptographic keys must always leverage natural tabular alignment.
- **Telemetry Micro-Labels**: Uppercase tokens (`label-caps`) use wider tracking (+0.06em) for maximum legibility at small scale on OLED screens in harsh sunlight.

## Layout & Spacing

Layouts follow a strict 4px numerical cadence (`4px`, `8px`, `12px`, `16px`, `24px`, `32px`). 

### Samsung One UI Split Ergonomics
Screens are split into two primary structural zones:
1. **The Header Viewing Zone (Top 30% / 96px resting height)**: Houses channel names, geohash context, and peer hop counts. When scrolled, this zone smoothly compresses into a 56px Liquid Glass top app bar.
2. **The Manipulation Zone (Bottom 70%)**: Keeps all interactive elements—the message composer, action discs, filters, and channel drawers—within the natural reach of the thumb.

### Adaptive Behavior
- **Mobile (<600px)**: Fluid width, 16px lateral margins, bottom-pinned controls with edge-to-edge system navigation inset offsets.
- **Tablet & Foldable (600px–1024px)**: Chat streams constrained to a maximum reading container of 640px centered against pure black letterboxes, or arranged into a master-detail split view with pinned sidebars.
- **Touch Bounds**: Every interactive target enforces a hardware minimum size of 48px × 48px, even when housing smaller glyphs.

## Elevation & Depth

Visual hierarchy is constructed through material optical physics rather than heavy drop shadows:

1. **Liquid Glass Surfaces**: Modals, popovers, and floating sheets apply a 20px Gaussian backdrop blur layered with `rgba(255, 255, 255, 0.055)` fill and a 1px specular outer rim of `rgba(255, 255, 255, 0.12)`.
2. **Tonal Obsidian Stacking**: Base canvas rests at `#000000`, inset inputs and recessed viewports rest at `#09090B`, bottom navigation panels and floating sheets sit at `#101014`, and elevated interactive dialogs sit at `#17171D`.
3. **Specular Edge Catching**: Modals and cards incorporate a top-edge inner highlight line (`rgba(255, 255, 255, 0.22)`) representing an acute overhead light catch.
4. **Zero Ambient Shadows**: Blurry, diffuse grey drop shadows are forbidden. Separation is achieved strictly through backdrop refraction, hairline contrast, and localized accent glows (e.g., 8px soft glow on focused Periwinkle or Cyan components).

## Shapes

The design system embraces Material 3 expressive pill geometries contrasted with structured technological corners:
- **Interactive Controls**: Buttons, filter chips, search fields, and status badges use complete pill roundings (`border-radius: 9999px`).
- **Surface Panels & Bottom Sheets**: Floating modal sheets use 24px top corner radii (`rounded-xl`), while embedded cards and media viewports use 16px corner radii (`rounded-lg`).
- **Asymmetric Message Bubbles**: Standard chat bubbles use 18px radii on three corners and a tight 4px anchor on the speaker's tail corner to immediately signal authorship and spatial directionality.

## Components

### Buttons
- **Primary Action Pill**: Full pill shape (`rounded-full`, min height 48px, preferred 52px). Electric Periwinkle (`#8795FF`) background with high-contrast jet-black JetBrains Mono Medium text.
- **Liquid Glass Secondary Button**: Full pill shape, translucent fill (`rgba(255, 255, 255, 0.055)`), 1px hairline border (`rgba(255, 255, 255, 0.12)`), and Pure White (`#F5F5F7`) text.
- **Tactile Feedback**: Hardware haptic detent on press, scaling down to 0.94x instantly, with an elastic 120ms spring return upon release. Ink ripples are not used.

### Chips & Badges
- **Status Pills**: Height 28px, pill-shaped. Background tinted with the semantic accent color at 14% opacity, hairline border at 34% opacity, featuring uppercase `label-caps` typography.
- **Geohash Pill**: Ice Blue border (`rgba(154, 220, 245, 0.40)`), soft cyan-blue background glow, displaying coordinates and distance markers.

### Input Fields & Composers
- **The Obsidian Composer**: Pill capsule floating above the bottom edge. Resting state uses `#09090B` fill with a `rgba(255, 255, 255, 0.12)` border. Active/focused state elevates to `#101014` with a 1.5px glowing Electric Periwinkle (`#8795FF`) outline.
- **Utility Discs**: Embedded 36px circular action buttons (`#17171D`) for attachments and voice notes.
- **Voice Note Transceiver**: Holding the mic disc transforms the composer capsule into a live scrolling audio waveform illuminated in Soft Cyan (`#84D9DB`), with a slide-to-cancel pill indicator.

### Cards & Message Bubbles
- **Incoming Peer Bubble**: Frosted surface tinted deterministically with the peer's assigned color at 14% opacity (`rgba(peerColor, 0.14)`), 1px border at 34% opacity, 18px rounded corners with a 4px bottom-left anchor tail. Max width 80%.
- **Outgoing Bubble**: Electric Periwinkle tint (`rgba(135, 149, 255, 0.18)`), specular edge (`rgba(135, 149, 255, 0.40)`), 18px rounded corners with a 4px bottom-right anchor tail.

### Lists & Navigation
- **Peer List Item**: Resting on Obsidian Tier 1 or base black, with 12px vertical spacing. Avatars feature circular node rings indicating link status: Solid Mint for direct BLE connection, Soft Cyan for multi-hop mesh, and Violet for verified Noise E2EE handshakes.
- **One UI Header**: Expansive 96px resting title area that collapses smoothly to a 56px translucent Liquid Glass navigation bar on scroll, with a 1px border-bottom divider.