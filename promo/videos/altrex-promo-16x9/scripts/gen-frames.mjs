#!/usr/bin/env node
// gen-frames.mjs — writes compositions/frames/NN-*.html for the Altrex promo.
// One shared look (frame.md) for all nine frames; each frame carries portrait (1080x1920)
// and landscape (1920x1080) layouts through an orientation media query, so the same files
// build both formats. Run: node scripts/gen-frames.mjs
import { mkdirSync, writeFileSync } from "node:fs";

const OUT = "compositions/frames";
const [W, H] = (process.argv[2] || "1080x1920").split("x").map(Number);
mkdirSync(OUT, { recursive: true });

const A_OUTER = "M54,28L76,78L65,78L60.5,67L47.5,67L43,78L32,78Z";
const A_INNER = "M54,41L49.5,59L58.5,59Z";

const ICON = {
  plane:
    '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M21 16v-2l-8-5V3.5c0-.83-.67-1.5-1.5-1.5S10 2.67 10 3.5V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z"/></svg>',
  wifi:
    '<svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" d="M12 19h.01M8.6 15.4a4.8 4.8 0 0 1 6.8 0M5.6 12.4a9 9 0 0 1 12.8 0M2.6 9.3a13.2 13.2 0 0 1 18.8 0"/></svg>',
  bars:
    '<svg viewBox="0 0 24 24"><rect x="2" y="15" width="3.6" height="6" rx="1" fill="currentColor"/><rect x="7.4" y="11" width="3.6" height="10" rx="1" fill="currentColor"/><rect x="12.8" y="7" width="3.6" height="14" rx="1" fill="currentColor"/><rect x="18.2" y="3" width="3.6" height="18" rx="1" fill="currentColor"/></svg>',
  battery:
    '<svg viewBox="0 0 28 24"><rect x="1" y="6" width="22" height="12" rx="3" fill="none" stroke="currentColor" stroke-width="1.6"/><rect x="3.5" y="8.5" width="15" height="7" rx="1.5" fill="currentColor"/><rect x="24.2" y="10" width="2.4" height="4" rx="1" fill="currentColor"/></svg>',
  check:
    '<svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
  chip:
    '<svg viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2.5" fill="none" stroke="currentColor" stroke-width="1.8"/><rect x="9.5" y="9.5" width="5" height="5" rx="1" fill="currentColor"/><path stroke="currentColor" stroke-width="1.8" stroke-linecap="round" d="M9 2.5v2.5M15 2.5v2.5M9 19v2.5M15 19v2.5M2.5 9h2.5M2.5 15h2.5M19 9h2.5M19 15h2.5"/></svg>',
  mic:
    '<svg viewBox="0 0 24 24"><rect x="9" y="3" width="6" height="11" rx="3" fill="currentColor"/><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21"/></svg>',
};

// Shared look. `X-` is replaced with the frame prefix so sibling frames never collide.
const BASE_CSS = `
@font-face { font-family: "Manrope"; src: url("assets/fonts/manrope_medium.ttf"); font-weight: 500; }
@font-face { font-family: "Manrope"; src: url("assets/fonts/manrope_semibold.ttf"); font-weight: 600; }
@font-face { font-family: "Manrope"; src: url("assets/fonts/manrope_bold.ttf"); font-weight: 700; }
@font-face { font-family: "Manrope"; src: url("assets/fonts/manrope_extrabold.ttf"); font-weight: 800; }
@font-face { font-family: "JetBrains Mono"; src: url("assets/fonts/jetbrains_mono_medium.ttf"); font-weight: 500; }
#root { position: relative; width: 100%; height: 100%; overflow: hidden; font-family: "Manrope", sans-serif; color: #E8EEF2; }
.X-bg { position: absolute; inset: 0; background: #0C151B; overflow: hidden; }
.X-halo { position: absolute; left: 50%; top: var(--X-hy, 46%); width: 1500px; height: 1500px; margin: -750px 0 0 -750px; border-radius: 50%;
  background: radial-gradient(circle, rgba(140,203,245,0.15) 0%, rgba(114,214,219,0.06) 34%, rgba(12,21,27,0) 68%); }
.X-vig { position: absolute; inset: 0; background: radial-gradient(ellipse at center, rgba(0,0,0,0) 52%, rgba(0,0,0,0.5) 100%); }
.X-grain { position: absolute; inset: 0; opacity: 0.035; background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='220' height='220'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/></filter><rect width='100%' height='100%' filter='url(%23n)'/></svg>"); }
.X-stage { position: absolute; inset: 0; }
.X-eyebrow { font-family: "JetBrains Mono", monospace; font-weight: 500; letter-spacing: 0.18em; text-transform: uppercase; color: #8CCBF5; }
.X-grad { background: linear-gradient(100deg, #8CCBF5 0%, #72D6DB 100%); -webkit-background-clip: text; background-clip: text; color: transparent; }
.X-pill { display: flex; align-items: center; gap: 0.5em; padding: 0.55em 1.05em; border-radius: 999px; background: rgba(26,39,49,0.92);
  border: 1px solid rgba(140,203,245,0.16); font-weight: 600; white-space: nowrap; }
.X-pill svg { width: 1.15em; height: 1.15em; flex: none; }
/* phone */
.X-ph { position: absolute; width: var(--X-pw); left: var(--X-px); top: var(--X-py); padding: calc(var(--X-pw) * 0.022);
  border-radius: calc(var(--X-pw) * 0.105); background: #0A1116; border: 1px solid rgba(140,203,245,0.16);
  box-shadow: 0 0 0 1px rgba(0,0,0,0.6) inset, 0 40px 120px rgba(0,0,0,0.45); }
.X-scr { position: relative; border-radius: calc(var(--X-pw) * 0.085); overflow: hidden; background: #0E151B; }
.X-sb { position: relative; height: calc(var(--X-pw) * 0.075); display: flex; align-items: center; justify-content: space-between;
  padding: 0 calc(var(--X-pw) * 0.075); font-size: calc(var(--X-pw) * 0.034); font-weight: 700; color: #E8EEF2; background: #0E151B; }
.X-sbr { display: flex; align-items: center; gap: calc(var(--X-pw) * 0.014); }
.X-sbr svg { width: calc(var(--X-pw) * 0.042); height: calc(var(--X-pw) * 0.042); display: block; }
.X-shot { position: relative; width: 100%; aspect-ratio: 933 / 1860; overflow: hidden; }
.X-shot img { position: absolute; left: 0; top: 0; width: 100%; height: 100%; display: block; }
@media (orientation: portrait) {
  #root { --X-pw: 600px; --X-px: 240px; --X-py: 300px; }
}
@media (orientation: landscape) {
  #root { --X-pw: 404px; --X-px: 250px; --X-py: 36px; --X-hy: 50%; }
}
`;

function phone(p, shots, { statusIcons = "" } = {}) {
  const imgs = shots
    .map((s, i) => `<div id="${p}-shot${i}" class="${p}-img${i}" style="position:absolute;inset:0;background:url('assets/screens/${s}.jpg') center / 100% 100% no-repeat;"></div>`)
    .join("\n          ");
  return `
      <div id="${p}-ph" class="${p}-ph">
        <div class="${p}-scr">
          <div class="${p}-sb"><span>9:41</span><span class="${p}-sbr">${statusIcons ||
            `${ICON.wifi}${ICON.bars}${ICON.battery}`}</span></div>
          <div id="${p}-shotbox" class="${p}-shot">
          ${imgs}
          </div>
        </div>
      </div>`;
}

function frame({ id, prefix: p, duration, css, html, js, hy }) {
  const style = (BASE_CSS + css).replaceAll("X-", `${p}-`);
  return `<template id="${id}">
  <div id="root" data-composition-id="${id}" data-start="0" data-duration="${duration}" data-width="${W}" data-height="${H}">
    <style>${style}${hy ? `\n#root { --${p}-hy: ${hy}; }` : ""}
    </style>
    <div id="${p}-bg" class="${p}-bg clip" data-start="0" data-duration="${duration}" data-track-index="0">
      <div id="${p}-halo" class="${p}-halo"></div>
      <div class="${p}-vig"></div>
      <div class="${p}-grain"></div>
    </div>
    <div id="${p}-stage" class="${p}-stage clip" data-start="0" data-duration="${duration}" data-track-index="1">${html}
    </div>
    <script src="https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js"></script>
    <script>
      (function () {
        var D = ${duration};
        var tl = gsap.timeline({ paused: true });
        var q = function (s) { return document.querySelector("[data-composition-id='${id}'] " + s); };
        // ambient layer: the halo breathes once per ~6 s
        var halo = q("#${p}-halo");
        tl.fromTo(halo, { scale: 0.94, opacity: 0.75 }, { scale: 1.06, opacity: 1, duration: D / 2, ease: "sine.inOut" }, 0);
        tl.to(halo, { scale: 0.98, opacity: 0.85, duration: D / 2, ease: "sine.inOut" }, D / 2);
${js}
        tl.to({}, { duration: D }, 0);
        window.__timelines["${id}"] = tl;
      })();
    </script>
  </div>
</template>
`;
}

// Common motion helpers, emitted as JS text.
const RISE = (sel, at, { y = 34, dur = 0.55, blur = 0 } = {}) =>
  `        tl.fromTo(q("${sel}"), { y: ${y}, opacity: 0${blur ? `, filter: "blur(${blur}px)"` : ""} }, { y: 0, opacity: 1${
    blur ? ', filter: "blur(0px)"' : ""
  }, duration: ${dur}, ease: "power2.out" }, ${at});`;
const PHONE_IN = (p, at = 0, push = 1.035, D = "D") =>
  `        tl.fromTo(q("#${p}-ph"), { y: 56, scale: 0.97, opacity: 0 }, { y: 0, scale: 1, opacity: 1, duration: 0.6, ease: "power2.out" }, ${at});
        tl.to(q("#${p}-ph"), { scale: ${push}, duration: ${D} - 0.6, ease: "sine.inOut" }, ${at} + 0.6);`;

const LETTERS = (p) => "ALTREX".split("").map((c, i) => `<span class="${p}-ch" id="${p}-ch${i}">${c}</span>`).join("");
const SPREAD = (p, at, dur) =>
  `        tl.fromTo(gsap.utils.toArray(document.querySelectorAll("[data-composition-id] .${p}-ch")), { x: function (i) { return (i - 2.5) * 16; }, opacity: 0 }, { x: 0, opacity: 1, duration: ${dur}, ease: "power2.out" }, ${at});`;

const frames = [];

// ── 1. Hook ────────────────────────────────────────────────────────────────────
frames.push({
  id: "01-hook",
  prefix: "f1",
  duration: 6.0,
  css: `
.X-img1, .X-img2 { opacity: 0; }
.X-plane { display: inline-flex; width: 0; overflow: visible; }
.X-plane svg { color: #8CCBF5; }
.X-chip { position: absolute; left: 0; right: 0; display: flex; justify-content: center; }
.X-chip .X-pill { font-family: "JetBrains Mono", monospace; font-weight: 500; letter-spacing: 0.14em; color: #8A9AA6; }
.X-hero { position: absolute; display: flex; align-items: center; gap: 0.32em; font-weight: 800; letter-spacing: -0.03em; line-height: 1; }
.X-dot { width: 0.28em; height: 0.28em; border-radius: 50%; background: #5FD4A0; box-shadow: 0 0 0.4em rgba(95,212,160,0.6); flex: none; }
@media (orientation: portrait) {
  .X-chip { top: 92px; font-size: 30px; }
  .X-hero { left: 0; right: 0; justify-content: center; top: 172px; font-size: 92px; }
}
@media (orientation: landscape) {
  .X-chip { left: 860px; right: auto; top: 330px; font-size: 28px; }
  .X-hero { left: 860px; top: 420px; font-size: 128px; }
}`,
  html: `
      <div class="f1-chip"><div id="f1-chip" class="f1-pill"><span id="f1-chipicon" style="display:inline-flex">${ICON.plane}</span><span>Mode avion</span></div></div>
      <div id="f1-hero" class="f1-hero"><span class="f1-dot"></span><span>Hors ligne.</span></div>
${phone("f1", ["chat-saisie", "chat-attente", "chat-code"], {
  statusIcons: `<span id="f1-plane" class="f1-plane">${ICON.plane}</span><span id="f1-net" style="display:flex;gap:inherit">${ICON.wifi}${ICON.bars}</span>${ICON.battery}`,
})}`,
  js: `
${PHONE_IN("f1", 0, 1.03)}
        tl.fromTo(q("#f1-chip"), { y: 16, opacity: 0 }, { y: 0, opacity: 0.6, duration: 0.5, ease: "power2.out" }, 0.15);
        // "Mode avion" — airplane lights up, network dies
        tl.fromTo(q("#f1-plane"), { width: 0, opacity: 0, scale: 0.4 }, { width: "1.5em", opacity: 1, scale: 1, duration: 0.45, ease: "power2.out" }, 0.95);
        tl.to(q("#f1-net"), { opacity: 0.18, duration: 0.4, ease: "power1.in" }, 1.0);
        tl.to(q("#f1-chip"), { opacity: 1, color: "#8CCBF5", borderColor: "rgba(140,203,245,0.55)", backgroundColor: "rgba(140,203,245,0.12)", duration: 0.45, ease: "power2.out" }, 0.95);
        // send — typed message leaves, answer pending
        tl.fromTo(q("#f1-shot1"), { opacity: 0, y: 24 }, { opacity: 1, y: 0, duration: 0.35, ease: "power2.out" }, 2.25);
        // "pourtant" — the real answer arrives anyway
        tl.fromTo(q("#f1-shot2"), { opacity: 1, clipPath: "inset(100% 0% 0% 0%)" }, { clipPath: "inset(0% 0% 0% 0%)", duration: 0.9, ease: "power2.out" }, 3.7);
        // "répond" — hero word
        tl.fromTo(q("#f1-hero"), { y: 30, opacity: 0 }, { y: 0, opacity: 1, duration: 0.55, ease: "power2.out" }, 4.65);
        tl.to(q("#f1-chip"), { opacity: 0, y: -12, duration: 0.35, ease: "power1.in" }, 4.55);`,
});

// ── 2. Logo ────────────────────────────────────────────────────────────────────
frames.push({
  id: "02-logo",
  prefix: "f2",
  duration: 4.5,
  hy: "40%",
  css: `
.X-logo { position: absolute; left: 50%; }
.X-logo svg { width: 100%; height: 100%; display: block; overflow: visible; }
.X-word { position: absolute; left: 0; right: 0; text-align: center; font-weight: 800; color: #E8EEF2; letter-spacing: 0.3em; padding-left: 0.3em; }
.X-ch { display: inline-block; }
@media (orientation: portrait) {
  .X-logo { width: 460px; height: 460px; margin-left: -230px; top: 470px; }
  .X-word { top: 1030px; font-size: 104px; }
}
@media (orientation: landscape) {
  .X-logo { width: 380px; height: 380px; margin-left: -190px; top: 150px; }
  .X-word { top: 600px; font-size: 104px; }
}`,
  html: `
      <div id="f2-logo" class="f2-logo">
        <svg viewBox="22 18 64 70">
          <defs>
            <linearGradient id="f2-fill" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#8CCBF5"/><stop offset="1" stop-color="#72D6DB"/></linearGradient>
            <linearGradient id="f2-sheen" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#fff" stop-opacity="0"/><stop offset="0.5" stop-color="#fff" stop-opacity="0.55"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient>
            <clipPath id="f2-clip"><path d="${A_OUTER}" /></clipPath>
          </defs>
          <path id="f2-fillpath" d="${A_OUTER} ${A_INNER}" fill="url(#f2-fill)" fill-rule="evenodd" />
          <g clip-path="url(#f2-clip)"><rect id="f2-sheenrect" x="0" y="18" width="18" height="70" fill="url(#f2-sheen)" transform="skewX(-18)" /></g>
          <path id="f2-stroke1" d="${A_OUTER}" pathLength="1" fill="none" stroke="#8CCBF5" stroke-width="0.9" stroke-linejoin="round" stroke-dasharray="1" />
          <path id="f2-stroke2" d="${A_INNER}" pathLength="1" fill="none" stroke="#8CCBF5" stroke-width="0.9" stroke-linejoin="round" stroke-dasharray="1" />
        </svg>
      </div>
      <div id="f2-word" class="f2-word">${LETTERS("f2")}</div>`,
  js: `
        tl.fromTo(q("#f2-stroke1"), { attr: { "stroke-dashoffset": 1 } }, { attr: { "stroke-dashoffset": 0 }, duration: 1.1, ease: "power2.inOut" }, 0.05);
        tl.fromTo(q("#f2-stroke2"), { attr: { "stroke-dashoffset": 1 } }, { attr: { "stroke-dashoffset": 0 }, duration: 0.7, ease: "power2.inOut" }, 0.45);
        tl.fromTo(q("#f2-fillpath"), { opacity: 0 }, { opacity: 1, duration: 0.6, ease: "power2.out" }, 1.1);
        tl.to([q("#f2-stroke1"), q("#f2-stroke2")], { opacity: 0, duration: 0.5, ease: "power1.in" }, 1.5);
        tl.fromTo(q("#f2-sheenrect"), { attr: { x: 0 } }, { attr: { x: 110 }, duration: 0.9, ease: "power2.inOut" }, 1.45);
        tl.fromTo(q("#f2-logo"), { scale: 0.94 }, { scale: 1.04, duration: D, ease: "sine.inOut" }, 0);
        tl.fromTo(q("#f2-word"), { y: 18 }, { y: 0, duration: 0.9, ease: "power2.out" }, 1.95);
${SPREAD("f2", 1.95, 0.9)}`,
});

// ── 3. Promesse ────────────────────────────────────────────────────────────────
frames.push({
  id: "03-promesse",
  prefix: "f3",
  duration: 7.5,
  css: `
.X-line { position: absolute; font-weight: 800; letter-spacing: -0.035em; line-height: 1.02; }
.X-l3 { font-weight: 600; letter-spacing: -0.01em; color: #8A9AA6; display: flex; align-items: center; gap: 0.45em; }
.X-l3 .X-ic { position: relative; width: 1.1em; height: 1.1em; color: #8CCBF5; flex: none; }
.X-l3 .X-ic svg { position: absolute; inset: 0; width: 100%; height: 100%; }
.X-rule { position: absolute; height: 4px; border-radius: 2px; background: linear-gradient(90deg, #8CCBF5, #72D6DB); transform-origin: 0 50%; }
@media (orientation: portrait) {
  .X-l1 { left: 90px; top: 520px; font-size: 150px; }
  .X-l2 { left: 90px; top: 700px; font-size: 120px; width: 900px; }
  .X-l3 { left: 90px; top: 1060px; font-size: 54px; }
  .X-rule { left: 90px; top: 1010px; width: 520px; }
}
@media (orientation: landscape) {
  .X-l1 { left: 180px; top: 200px; font-size: 150px; }
  .X-l2 { left: 180px; top: 370px; font-size: 120px; }
  .X-l3 { left: 180px; top: 660px; font-size: 54px; }
  .X-rule { left: 180px; top: 600px; width: 560px; }
}`,
  html: `
      <div id="f3-l1" class="f3-line f3-l1">Ton IA.</div>
      <div id="f3-l2" class="f3-line f3-l2">Sur ton <span class="f3-grad">téléphone.</span></div>
      <div id="f3-rule" class="f3-rule"></div>
      <div id="f3-l3" class="f3-line f3-l3"><span class="f3-ic">${ICON.wifi}<svg viewBox="0 0 24 24"><path id="f3-slash" d="M3.5 3.5L20.5 20.5" pathLength="1" stroke-dasharray="1" fill="none" stroke="#E8EEF2" stroke-width="2.4" stroke-linecap="round"/></svg></span><span>Rien ne part sur Internet.</span></div>`,
  js: `
${RISE("#f3-l1", 0.4, { y: 44, dur: 0.6, blur: 10 })}
${RISE("#f3-l2", 2.65, { y: 44, dur: 0.6, blur: 10 })}
        tl.fromTo(q("#f3-rule"), { scaleX: 0 }, { scaleX: 1, duration: 0.7, ease: "power2.out" }, 4.4);
${RISE("#f3-l3", 4.55, { y: 26, dur: 0.55 })}
        tl.fromTo(q("#f3-slash"), { attr: { "stroke-dashoffset": 1 } }, { attr: { "stroke-dashoffset": 0 }, duration: 0.45, ease: "power2.out" }, 5.35);
        tl.fromTo([q("#f3-l1"), q("#f3-l2")], { scale: 1 }, { scale: 1.02, transformOrigin: "0% 50%", duration: 4, ease: "sine.inOut" }, 3.4);`,
});

// ── 4. Chat ────────────────────────────────────────────────────────────────────
frames.push({
  id: "04-chat",
  prefix: "f4",
  duration: 8.5,
  css: `
.X-chips { position: absolute; display: flex; gap: 18px; }
.X-chips .X-pill { font-size: 1em; }
.X-badge { position: absolute; }
.X-badge .X-pill { color: #E8EEF2; }
.X-badge svg { color: #8CCBF5; }
#f4-shot0 { transform-origin: 0% 88%; }
@media (orientation: portrait) {
  .X-chips { left: 0; right: 0; justify-content: center; top: 150px; font-size: 44px; }
  .X-badge { left: 0; right: 0; display: flex; justify-content: center; top: 1460px; font-size: 36px; }
}
@media (orientation: landscape) {
  .X-chips { left: 860px; top: 300px; font-size: 52px; }
  .X-badge { left: 860px; top: 520px; font-size: 40px; }
}`,
  html: `
      <div class="f4-chips"><div id="f4-c1" class="f4-pill">Discute</div><div id="f4-c2" class="f4-pill">Écris</div><div id="f4-c3" class="f4-pill">Code</div></div>
${phone("f4", ["chat-code"])}
      <div class="f4-badge"><div id="f4-badge" class="f4-pill">${ICON.chip}<span>Calculé sur l'appareil · CPU</span></div></div>`,
  js: `
${PHONE_IN("f4", 0, 1.02)}
${RISE("#f4-c1", 0.66, { y: 22, dur: 0.45 })}
${RISE("#f4-c2", 1.58, { y: 22, dur: 0.45 })}
${RISE("#f4-c3", 2.15, { y: 22, dur: 0.45 })}
        tl.fromTo(q("#f4-shot0"), { scale: 1 }, { scale: 1.22, duration: 3.4, ease: "power1.inOut" }, 2.9);
${RISE("#f4-badge", 4.75, { y: 26, dur: 0.55 })}`,
});

// ── 5. Modèles ─────────────────────────────────────────────────────────────────
frames.push({
  id: "05-modeles",
  prefix: "f5",
  duration: 8.8,
  css: `
.X-names { position: absolute; display: flex; font-weight: 800; letter-spacing: -0.03em; line-height: 1; }
.X-names span { display: block; }
.X-dl { position: absolute; }
.X-dl .X-pill { color: #E8EEF2; }
.X-ring { position: relative; width: 1.25em; height: 1.25em; flex: none; }
.X-ring svg { position: absolute; inset: 0; width: 100%; height: 100%; }
#f5-ok { color: #5FD4A0; }
@media (orientation: portrait) {
  .X-names { left: 0; right: 0; justify-content: center; gap: 0.5em; top: 160px; font-size: 76px; }
  .X-dl { left: 0; right: 0; display: flex; justify-content: center; top: 1460px; font-size: 36px; }
}
@media (orientation: landscape) {
  .X-names { flex-direction: column; gap: 0.28em; left: 860px; top: 190px; font-size: 104px; }
  .X-dl { left: 860px; top: 650px; font-size: 40px; }
}`,
  html: `
      <div class="f5-names"><span id="f5-n1">Gemma</span><span id="f5-n2">Qwen</span><span id="f5-n3" class="f5-grad">DeepSeek</span></div>
${phone("f5", ["modeles"])}
      <div class="f5-dl"><div id="f5-dl" class="f5-pill"><span class="f5-ring"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9.5" fill="none" stroke="rgba(140,203,245,0.18)" stroke-width="2.4"/><circle id="f5-arc" cx="12" cy="12" r="9.5" pathLength="1" stroke-dasharray="1" fill="none" stroke="#8CCBF5" stroke-width="2.4" stroke-linecap="round" transform="rotate(-90 12 12)"/></svg><span id="f5-ok" style="position:absolute;inset:0;opacity:0">${ICON.check}</span></span><span>Téléchargé · hors ligne</span></div></div>`,
  js: `
${PHONE_IN("f5", 0, 1.03)}
${RISE("#f5-n1", 1.95, { y: 28, dur: 0.5 })}
${RISE("#f5-n2", 2.82, { y: 28, dur: 0.5 })}
${RISE("#f5-n3", 3.6, { y: 28, dur: 0.5 })}
${RISE("#f5-dl", 4.7, { y: 24, dur: 0.5 })}
        tl.fromTo(q("#f5-arc"), { attr: { "stroke-dashoffset": 1 } }, { attr: { "stroke-dashoffset": 0 }, duration: 1.2, ease: "power1.inOut" }, 4.9);
        tl.to(q("#f5-arc"), { opacity: 0, duration: 0.25, ease: "power1.in" }, 6.15);
        tl.fromTo(q("#f5-ok"), { opacity: 0, scale: 0.6 }, { opacity: 1, scale: 1, duration: 0.4, ease: "power2.out" }, 6.25);
        tl.to(q("#f5-dl"), { borderColor: "rgba(95,212,160,0.45)", duration: 0.4, ease: "power2.out" }, 6.25);`,
});

// ── 6. Moteur auto ─────────────────────────────────────────────────────────────
frames.push({
  id: "06-moteur",
  prefix: "f6",
  duration: 7.0,
  css: `
.X-head { position: absolute; left: 0; right: 0; text-align: center; }
.X-head .X-eyebrow { display: block; }
.X-title { display: block; font-weight: 800; letter-spacing: -0.03em; line-height: 1.05; margin-top: 0.35em; }
.X-row { position: absolute; left: 50%; display: flex; gap: var(--X-gap); }
.X-c { position: relative; width: var(--X-cw); height: var(--X-ch); border-radius: 28px; background: #121D25; border: 1px solid rgba(140,203,245,0.14);
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; }
.X-c b { font-family: "JetBrains Mono", monospace; font-weight: 500; font-size: var(--X-cf); color: #E8EEF2; letter-spacing: 0.04em; }
.X-c i { font-style: normal; font-size: calc(var(--X-cf) * 0.42); color: #8A9AA6; font-weight: 600; }
.X-sel { position: absolute; left: 0; top: 0; width: var(--X-cw); height: var(--X-ch); border-radius: 28px; border: 3px solid #8CCBF5;
  box-shadow: 0 0 60px rgba(140,203,245,0.35), inset 0 0 30px rgba(140,203,245,0.12); }
.X-out { position: absolute; left: 0; right: 0; display: flex; justify-content: center; }
.X-out .X-pill { color: #E8EEF2; }
.X-out svg { color: #5FD4A0; }
@media (orientation: portrait) {
  #root { --f6-cw: 280px; --f6-ch: 300px; --f6-gap: 30px; --f6-cf: 72px; }
  .X-head { top: 360px; } .X-head .X-eyebrow { font-size: 32px; } .X-title { font-size: 104px; }
  .X-row { top: 860px; margin-left: -450px; }
  .X-out { top: 1260px; font-size: 40px; }
}
@media (orientation: landscape) {
  #root { --f6-cw: 300px; --f6-ch: 260px; --f6-gap: 40px; --f6-cf: 76px; }
  .X-head { top: 120px; } .X-head .X-eyebrow { font-size: 30px; } .X-title { font-size: 104px; } .X-title br { display: none; }
  .X-row { top: 420px; margin-left: -490px; }
  .X-out { top: 760px; font-size: 40px; }
}`,
  html: `
      <div id="f6-head" class="f6-head"><span class="f6-eyebrow">Mode auto</span><span class="f6-title">Le bon moteur, <br/>tout seul.</span></div>
      <div class="f6-row">
        <div id="f6-c1" class="f6-c"><b>CPU</b><i>Processeur</i></div>
        <div id="f6-c2" class="f6-c"><b>GPU</b><i>Graphique</i></div>
        <div id="f6-c3" class="f6-c"><b>NPU</b><i>Neuronal</i></div>
        <div id="f6-sel" class="f6-sel"></div>
      </div>
      <div class="f6-out"><div id="f6-out" class="f6-pill">${ICON.check}<span>Le plus rapide pour ton téléphone</span></div></div>`,
  js: `
        var step = function () { var r = getComputedStyle(q(".f6-row")); return parseFloat(getComputedStyle(q("#f6-c1")).width) + parseFloat(r.columnGap || r.gap); };
        var S = step();
${RISE("#f6-head", 0.4, { y: 36, dur: 0.6 })}
        tl.fromTo([q("#f6-c1"), q("#f6-c2"), q("#f6-c3")], { y: 40, opacity: 0 }, { y: 0, opacity: 1, duration: 0.5, ease: "power2.out", stagger: 0.12 }, 1.6);
        tl.fromTo(q("#f6-sel"), { x: 0, opacity: 0, scale: 1.06 }, { opacity: 1, scale: 1, duration: 0.4, ease: "power2.out" }, 3.0);
        tl.to(q("#f6-sel"), { x: S, duration: 0.45, ease: "power2.inOut" }, 3.95);
        tl.to(q("#f6-sel"), { x: 2 * S, duration: 0.45, ease: "power2.inOut" }, 4.55);
        tl.to(q("#f6-sel"), { x: S, duration: 0.6, ease: "power2.inOut" }, 5.2);
        tl.to([q("#f6-c1"), q("#f6-c3")], { opacity: 0.4, duration: 0.5, ease: "power1.out" }, 5.5);
        tl.to(q("#f6-c2"), { backgroundColor: "#16303D", duration: 0.5, ease: "power2.out" }, 5.5);
${RISE("#f6-out", 5.6, { y: 24, dur: 0.5 })}`,
});

// ── 7. Voix ────────────────────────────────────────────────────────────────────
const BARS = 26;
frames.push({
  id: "07-voix",
  prefix: "f7",
  duration: 5.5,
  css: `
.X-wave { position: absolute; display: flex; align-items: center; justify-content: center; gap: var(--X-bg2); }
.X-wave span { display: block; width: var(--X-bw); height: var(--X-bh); border-radius: 999px; background: linear-gradient(180deg, #8CCBF5, #72D6DB); transform-origin: 50% 50%; }
.X-mic { position: absolute; display: flex; align-items: center; gap: 0.5em; }
.X-mic svg { width: 1.1em; height: 1.1em; color: #8CCBF5; }
.X-tag { position: absolute; }
.X-tag .X-pill { color: #E8EEF2; } .X-tag svg { color: #5FD4A0; }
#f7-shot0 { transform-origin: 50% 96%; }
@media (orientation: portrait) {
  #root { --f7-bw: 14px; --f7-bh: 150px; --f7-bg2: 12px; }
  .X-mic { left: 0; right: 0; justify-content: center; top: 70px; font-size: 30px; }
  .X-wave { left: 0; right: 0; top: 120px; height: 170px; }
  .X-tag { left: 0; right: 0; display: flex; justify-content: center; top: 470px; font-size: 36px; }
}
@media (orientation: landscape) {
  #root { --f7-bw: 16px; --f7-bh: 220px; --f7-bg2: 14px; }
  .X-mic { left: 860px; top: 230px; font-size: 30px; }
  .X-wave { left: 860px; top: 300px; height: 240px; justify-content: flex-start; }
  .X-tag { left: 860px; top: 620px; font-size: 40px; }
}`,
  html: `
      <div id="f7-mic" class="f7-mic f7-eyebrow">${ICON.mic}<span>Dictée</span></div>
      <div id="f7-wave" class="f7-wave">${Array.from({ length: BARS }, (_, i) => `<span id="f7-b${i}"></span>`).join("")}</div>
${phone("f7", ["dictee"])}
      <div class="f7-tag"><div id="f7-tag" class="f7-pill">${ICON.check}<span>100 % sur l'appareil</span></div></div>`,
  js: `
${PHONE_IN("f7", 0, 1.0)}
        tl.fromTo(q("#f7-shot0"), { scale: 1 }, { scale: 1.14, duration: 4.4, ease: "sine.inOut" }, 0.6);
${RISE("#f7-mic", 0.2, { y: 16, dur: 0.45 })}
        // waveform: deterministic sine envelope, keyed every 0.12 s, louder while the voice speaks
        var speak = [[0.55, 1.35], [1.58, 2.45], [2.66, 3.65]];
        var env = function (t) { for (var k = 0; k < speak.length; k++) { if (t >= speak[k][0] - 0.05 && t <= speak[k][1] + 0.1) return 1; } return 0.28; };
        for (var b = 0; b < ${BARS}; b++) {
          var el = q("#f7-b" + b);
          var mid = 1 - Math.abs(b - ${(BARS - 1) / 2}) / ${BARS / 1.6};
          tl.set(el, { scaleY: 0.08, opacity: 0.9 }, 0);
          for (var t = 0.45; t < D - 0.12; t += 0.12) {
            var v = 0.5 + 0.5 * Math.sin(t * 9.3 + b * 0.83) * Math.sin(t * 4.1 + b * 0.37 + 1.2);
            var s = Math.max(0.06, Math.min(1, (0.12 + 0.88 * v) * mid * env(t)));
            tl.to(el, { scaleY: s, duration: 0.12, ease: "sine.inOut" }, t);
          }
        }
${RISE("#f7-tag", 2.6, { y: 24, dur: 0.5 })}`,
});

// ── 8. Confidentialité ─────────────────────────────────────────────────────────
frames.push({
  id: "08-confidentialite",
  prefix: "f8",
  duration: 5.0,
  css: `
#f8-ph { opacity: 0.22; filter: saturate(0.6) blur(3px); }
.X-scrim { position: absolute; inset: 0; background: linear-gradient(180deg, rgba(12,21,27,0.2) 0%, rgba(12,21,27,0.75) 40%, rgba(12,21,27,0.75) 70%, rgba(12,21,27,0.3) 100%); }
.X-lock { position: absolute; }
.X-lock svg { width: 100%; height: 100%; overflow: visible; display: block; }
.X-count { position: absolute; font-family: "JetBrains Mono", monospace; font-weight: 500; color: #F2C38B; letter-spacing: 0.02em; }
.X-sub { position: absolute; font-weight: 700; color: #E8EEF2; letter-spacing: -0.02em; }
@media (orientation: portrait) {
  .X-lock { left: 50%; width: 240px; height: 280px; margin-left: -120px; top: 520px; }
  .X-count { left: 0; right: 0; text-align: center; top: 900px; font-size: 84px; }
  .X-sub { left: 0; right: 0; text-align: center; top: 1030px; font-size: 56px; }
}
@media (orientation: landscape) {
  .X-scrim { background: linear-gradient(90deg, rgba(12,21,27,0.1) 0%, rgba(12,21,27,0.1) 38%, rgba(12,21,27,0.85) 52%); }
  .X-lock { left: 1000px; width: 190px; height: 220px; top: 170px; }
  .X-count { left: 1000px; top: 470px; font-size: 84px; }
  .X-sub { left: 1000px; top: 600px; font-size: 56px; }
}`,
  html: `
${phone("f8", ["menu"])}
      <div class="f8-scrim"></div>
      <div id="f8-lock" class="f8-lock"><svg viewBox="0 0 120 140">
        <defs><linearGradient id="f8-g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#8CCBF5"/><stop offset="1" stop-color="#72D6DB"/></linearGradient></defs>
        <path id="f8-shackle" d="M32 64V44a28 28 0 0 1 56 0v20" fill="none" stroke="url(#f8-g)" stroke-width="11" stroke-linecap="round"/>
        <rect x="14" y="60" width="92" height="74" rx="18" fill="url(#f8-g)"/>
        <circle cx="60" cy="92" r="8" fill="#0C151B"/><rect x="56" y="94" width="8" height="18" rx="4" fill="#0C151B"/>
      </svg></div>
      <div id="f8-count" class="f8-count">0 octet envoyé</div>
      <div id="f8-sub" class="f8-sub">Rien ne quitte ton téléphone.</div>`,
  js: `
        tl.fromTo(q("#f8-ph"), { opacity: 0, y: 30 }, { opacity: 0.22, y: 0, duration: 0.6, ease: "power2.out" }, 0);
        tl.to(q("#f8-ph"), { scale: 1.03, duration: D - 0.6, ease: "sine.inOut" }, 0.6);
        tl.fromTo(q("#f8-lock"), { y: 30, opacity: 0, scale: 0.96 }, { y: 0, opacity: 1, scale: 1, duration: 0.6, ease: "power2.out" }, 0.25);
        tl.fromTo(q("#f8-shackle"), { y: -16 }, { y: -16, duration: 0.01 }, 0);
        tl.to(q("#f8-shackle"), { y: 0, duration: 0.32, ease: "power2.in" }, 2.35);
        tl.fromTo(q("#f8-lock"), { filter: "drop-shadow(0 0 0px rgba(140,203,245,0))" }, { filter: "drop-shadow(0 0 36px rgba(140,203,245,0.45))", duration: 0.6, ease: "power2.out" }, 2.65);
${RISE("#f8-sub", 3.3, { y: 24, dur: 0.55 })}
${RISE("#f8-count", 2.75, { y: 24, dur: 0.55 })}`,
});

// ── 9. Signature ───────────────────────────────────────────────────────────────
frames.push({
  id: "09-signature",
  prefix: "f9",
  duration: 6.5,
  hy: "38%",
  css: `
.X-logo { position: absolute; left: 50%; }
.X-logo svg { width: 100%; height: 100%; display: block; }
.X-word { position: absolute; left: 0; right: 0; text-align: center; font-weight: 800; letter-spacing: 0.3em; padding-left: 0.3em; }
.X-ch { display: inline-block; }
.X-slogan { position: absolute; left: 0; right: 0; text-align: center; font-weight: 800; letter-spacing: -0.03em; line-height: 1.05; }
.X-chipw { position: absolute; left: 0; right: 0; display: flex; justify-content: center; }
.X-chipw .X-pill { font-family: "JetBrains Mono", monospace; font-weight: 500; color: #8A9AA6; letter-spacing: 0.06em; }
.X-dot { width: 0.5em; height: 0.5em; border-radius: 50%; background: #5FD4A0; }
@media (orientation: portrait) {
  .X-logo { width: 300px; height: 300px; margin-left: -150px; top: 430px; }
  .X-word { top: 790px; font-size: 64px; color: #8A9AA6; }
  .X-slogan { top: 930px; font-size: 104px; }
  .X-chipw { top: 1270px; font-size: 32px; }
}
@media (orientation: landscape) {
  .X-logo { width: 200px; height: 200px; margin-left: -100px; top: 80px; }
  .X-word { top: 315px; font-size: 48px; color: #8A9AA6; }
  .X-slogan { top: 420px; font-size: 108px; }
  .X-chipw { top: 700px; font-size: 30px; }
}`,
  html: `
      <div id="f9-logo" class="f9-logo"><svg viewBox="22 18 64 70"><defs><linearGradient id="f9-fill" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#8CCBF5"/><stop offset="1" stop-color="#72D6DB"/></linearGradient></defs><path d="${A_OUTER} ${A_INNER}" fill="url(#f9-fill)" fill-rule="evenodd"/></svg></div>
      <div id="f9-word" class="f9-word">${LETTERS("f9")}</div>
      <div id="f9-slogan" class="f9-slogan">L'IA qui reste<br/><span class="f9-grad">chez toi.</span></div>
      <div class="f9-chipw"><div id="f9-chip" class="f9-pill"><span class="f9-dot"></span><span>Android · 100 % hors ligne</span></div></div>`,
  js: `
        tl.fromTo(q("#f9-logo"), { opacity: 0, scale: 0.9 }, { opacity: 1, scale: 1, duration: 0.7, ease: "power2.out" }, 0.1);
        tl.fromTo(q("#f9-word"), { y: 16 }, { y: 0, duration: 0.8, ease: "power2.out" }, 0.8);
${SPREAD("f9", 0.8, 0.8)}
${RISE("#f9-slogan", 1.9, { y: 40, dur: 0.65, blur: 8 })}
${RISE("#f9-chip", 3.4, { y: 20, dur: 0.5 })}
        tl.fromTo(q("#f9-stage"), { scale: 1 }, { scale: 1.025, duration: 5.6, ease: "sine.inOut" }, 0.3);
        tl.to(q("#f9-stage"), { opacity: 0, duration: 0.6, ease: "power1.in" }, D - 0.6);`,
});

for (const f of frames) {
  writeFileSync(`${OUT}/${f.id}.html`, frame(f));
}
console.log(`wrote ${frames.length} frames → ${OUT}/`);
