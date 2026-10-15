---
version: alpha
name: Altrex — Frame (video / frame layer)
description: >
  Bespoke spec derived from the app itself and ../../MOTION.md. Night canvas, Altrex blue as the one
  voltage, cyan only in gradients with the blue, green only for "loaded / OK", amber at most once.
  Manrope for everything spoken by the brand, JetBrains Mono for technical tokens. Quiet, premium.
unit: the frame — 1080×1920 primary; 1920×1080 variant
principle: one message per shot · real app screens are the proof · nothing invented

colors:
  canvas: "#0C151B"
  surface: "#121D25"
  surface-high: "#1A2731"
  text: "#E8EEF2"
  text-muted: "#8A9AA6"
  primary: "#8CCBF5"
  secondary: "#72D6DB"
  success: "#5FD4A0"
  highlight: "#F2C38B"
  border: "rgba(140,203,245,0.14)"

radii:
  card: "28px"
  phone: "64px"
  pill: "999px"

typography:
  display:  { fontFamily: "Manrope", weight: 800, tracking: "-0.03em", lineHeight: 1.02, color: "text" }
  headline: { fontFamily: "Manrope", weight: 700, tracking: "-0.02em", lineHeight: 1.1, color: "text" }
  body:     { fontFamily: "Manrope", weight: 500, lineHeight: 1.4, color: "text-muted" }
  eyebrow:  { fontFamily: "JetBrains Mono", weight: 500, tracking: "0.18em", upper: true, color: "primary" }
  mono:     { fontFamily: "JetBrains Mono", weight: 500, color: "text" }

fonts:
  Manrope: assets/fonts/manrope_{light,regular,medium,semibold,bold,extrabold}.ttf
  JetBrains Mono: assets/fonts/jetbrains_mono_{regular,medium}.ttf

components:
  phone: "Device frame: 1px border at 14% blue, radius {radii.phone}, inner screen = real screenshot under a drawn status bar (time, network/airplane, battery). Soft blue halo behind, no hard shadow."
  card: "surface fill, 1px {colors.border}, radius {radii.card}."
  chip: "pill, surface-high fill, mono label."
  logo-A: "SVG path M54,28L76,78L65,78L60.5,67L47.5,67L43,78L32,78Z + counter M54,41L49.5,59L58.5,59Z (viewBox 0 0 108 108), fill = linear gradient primary→secondary; stroke draw-on in primary."
  ambient: "radial halo of primary at 10–16% alpha drifting slowly, 3% grain, vignette."

motion:
  personality: Premium — entrances 450–600 ms ease-out (power2.out / cubic-bezier(0,0,0.2,1)), no overshoot, no bounce.
  layers: primary (message) · secondary (UI) · ambient (halo, 6–10 s cycles)
  max_moving: 3 elements at once; stagger total < 500 ms.
  never: bounce/elastic, off-palette colours, unreadable fast text, cloud/server imagery.
