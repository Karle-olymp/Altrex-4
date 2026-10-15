---
format: 1080x1920
duration: 59s
message: "Altrex — l'IA qui reste chez toi."
arc: Hook (mode avion) → Révélation → Promesse → Preuves (chat, modèles, moteur auto, voix) → Confidentialité → Signature
audience: utilisateurs Android curieux de l'IA, attachés à leur vie privée
mode: autonomous
music: none
---

## Video direction

Night canvas #0C151B everywhere, one slow blue halo breathing behind the hero, 3% grain, vignette.
The real app screenshots are the proof: always shown inside the same phone frame (radius 64, 1px
blue hairline, drawn status bar). Typography: Manrope 800 for one short hero line per shot,
JetBrains Mono eyebrows. Motion personality Premium: ease-out entrances 0.5–0.6 s, no overshoot,
at most three things moving. Camera: slow push-ins (scale 1 → 1.04 over the shot). Landscape
variant: phone on the left third, type on the right; portrait: type above, phone below, all
content above the caption band (y < 1600).

## Frame 1 — Hook : mode avion
- scene: Phone, chat screen with a typed message. Airplane icon lights up in the status bar, network bars die; the message is sent; the real answer with code appears anyway.
- duration: 6.0s
- transition_in: cut
- voiceover: "Mode avion activé. Et pourtant… elle répond."
- src: compositions/frames/01-hook.html
- blueprint: compose
- focal: assets/screens/chat-saisie.jpg
- roles: chat-saisie (cutout, phone screen) · chat-attente (cutout, swap) · chat-code (cutout, swap)
- sfx: toggle click 0.5s · send whoosh 2.3s · soft tick 3.3s · reveal shimmer 4.6s

Scene 1 (0.0–0.9s): phone centred, slight push-in; status bar shows Wi-Fi + 4 bars. Eyebrow chip "MODE AVION" waits dimmed above the phone.
→ Scene 2 (0.5–1.6s): on "Mode avion" the airplane glyph scales in (ease-out) in the status bar and in the chip (chip turns blue); Wi-Fi + bars fade to 20%.
→ Scene 3 (2.2–3.4s): send — screen swaps chat-saisie → chat-attente (cross-dissolve 0.25s, slight upward drift of 24px).
→ Scene 4 (3.6–6.0s): on "pourtant" the answer screen chat-code wipes up from the bottom of the screen (clip-path reveal 0.9s); on "répond" a hero word "Hors ligne." lands above the phone, green dot.

## Frame 2 — Révélation du logo
- scene: Phone recedes into the dark; the Altrex "A" draws itself in light, fills with the blue→cyan gradient, a sheen sweeps; "ALTREX" rises beneath.
- duration: 4.5s
- transition_in: blur-crossfade 0.5s
- voiceover: "Voici Altrex."
- src: compositions/frames/02-logo.html
- blueprint: compose
- focal: logo-A (SVG)
- sfx: sub swell 0.0s · shimmer 1.4s

Scene 1 (0.0–1.2s): stroke of the A draws on (stroke-dashoffset), primary blue, 1.1s ease-in-out; halo grows.
→ Scene 2 (1.1–1.7s): gradient fill fades up, stroke settles.
→ Scene 3 (1.4–2.2s): diagonal sheen sweeps across the glyph once.
→ Scene 4 (1.7–4.5s): on "Altrex" the wordmark ALTREX rises 16px with letter-spacing 0.5em→0.32em; slow push-in continues.

## Frame 3 — La promesse
- scene: Type only, three lines arriving in turn: "Ton IA." / "Sur ton téléphone." / "Rien ne part sur Internet."
- duration: 7.5s
- transition_in: zoom-through 0.5s
- voiceover: "Une intelligence artificielle complète, directement sur ton téléphone. Rien ne part sur Internet."
- src: compositions/frames/03-promesse.html
- blueprint: compose
- focal: hero type stack
- sfx: soft tick on each line 0.5s · 2.7s · 4.6s

Scene 1 (0.4–2.4s): "Ton IA." rises in (y 40→0, blur 8→0).
→ Scene 2 (2.7–4.4s): "Sur ton téléphone." rises in, the word "téléphone" in blue gradient.
→ Scene 3 (4.6–7.5s): "Rien ne part sur Internet." arrives in muted text with a thin blue line drawing under it; a small crossed-out cloud-free "réseau" icon: a wifi glyph with a slash draws on.

## Frame 4 — Le chat
- scene: Phone with the real chat answer and Python code block; the screen scrolls gently through the answer; chips "Discute", "Écris", "Code" land one by one; a "Calculé sur l'appareil · CPU" badge.
- duration: 8.5s
- transition_in: blur-crossfade 0.5s
- voiceover: "Discute, écris, code. Les réponses arrivent en temps réel, calculées par la puce de ton téléphone."
- src: compositions/frames/04-chat.html
- blueprint: compose
- focal: assets/screens/chat-code.jpg
- roles: chat-code (cutout, phone screen, scrolls)
- sfx: pop on each chip 0.7s · 1.6s · 2.2s

Scene 1 (0.0–0.6s): phone slides up 60px into place with chat-code.
→ Scene 2 (0.7–2.6s): chips Discute / Écris / Code land on the cue words (stagger driven by voice, each 0.45s ease-out).
→ Scene 3 (3.0–6.0s): the screenshot scrolls up inside the phone (y 0 → −22%) to reveal the code block, slow ease-in-out.
→ Scene 4 (4.8–8.5s): on "calculées" a badge "Calculé sur l'appareil · CPU" with a chip glyph slides in beside/under the phone.

## Frame 5 — Les modèles
- scene: The real Models page in the phone; family names Gemma, Qwen, DeepSeek float in as large type on cue; a "Téléchargé · à toi" check in green.
- duration: 8.8s
- transition_in: push-slide LEFT 0.5s
- voiceover: "Choisis ton modèle : Gemma, Qwen, DeepSeek… Tu le télécharges une fois, il est à toi."
- src: compositions/frames/05-modeles.html
- blueprint: compose
- focal: assets/screens/modeles.jpg
- roles: modeles (cutout, phone screen)
- sfx: soft whoosh 2.0s · 2.9s · 3.7s · confirm ding 6.3s

Scene 1 (0.0–1.6s): phone with modeles.jpg, push-in; eyebrow "MODÈLES".
→ Scene 2 (2.0–4.4s): names Gemma, Qwen, DeepSeek appear one per cue as a list (y 30→0), previous names dim to 40%.
→ Scene 3 (4.8–8.8s): download progress ring fills (4.9→5.9s) then turns into a green check, label "Téléchargé · hors ligne".

## Frame 6 — Le moteur auto
- scene: Three chips CPU / GPU / NPU in mono; a light beam visits each, then settles on the best one; label "Mode auto".
- duration: 7.0s
- transition_in: blur-crossfade 0.5s
- voiceover: "Le mode auto choisit tout seul le meilleur moteur : processeur, GPU ou NPU."
- src: compositions/frames/06-moteur.html
- blueprint: compose
- focal: CPU/GPU/NPU chips
- sfx: tick 3.1s · 4.0s · 4.7s · lock-in 5.4s

Scene 1 (0.4–2.0s): "Mode auto" headline rises; three chips draw in (stagger 0.12s).
→ Scene 2 (3.0–4.9s): a highlight ring moves CPU → GPU → NPU on the cue words.
→ Scene 3 (5.3–7.0s): the ring settles on the chosen chip with a soft glow, a check and the label "Le meilleur pour ton téléphone"; other chips dim.

## Frame 7 — La voix
- scene: The real dictation bar on the phone, an animated cyan waveform overlay pulsing with the voice.
- duration: 5.5s
- transition_in: blur-crossfade 0.5s
- voiceover: "Parle-lui. Il t'écoute, sans rien envoyer."
- src: compositions/frames/07-voix.html
- blueprint: compose
- focal: assets/screens/dictee.jpg
- roles: dictee (cutout, phone screen)
- sfx: none (voice carries it)

Scene 1 (0.0–1.5s): phone with dictee.jpg, push-in toward the bottom bar; eyebrow "DICTÉE".
→ Scene 2 (0.5–5.5s): large cyan waveform bars (24 bars) above the phone breathe with deterministic sine motion.
→ Scene 3 (2.6–5.5s): on "sans rien envoyer" a chip "100 % sur l'appareil" fades up.

## Frame 8 — Confidentialité
- scene: The real side menu "IA locale · hors ligne"; a lock closes; amber counter "0 octet envoyé" (the only amber in the film).
- duration: 5.0s
- transition_in: blur-crossfade 0.5s
- voiceover: "Tes conversations restent à toi. Pour de vrai."
- src: compositions/frames/08-confidentialite.html
- blueprint: compose
- focal: lock + counter
- roles: menu (supporting, phone screen dimmed 50%)
- sfx: lock click 2.4s

Scene 1 (0.0–1.4s): phone with menu.jpg dimmed; a lock glyph draws above it (shackle open).
→ Scene 2 (2.3–2.8s): on "Pour de vrai" the shackle closes (y −14 → 0).
→ Scene 3 (2.5–5.0s): counter "0 octet envoyé" in amber mono fades up under the lock.

## Frame 9 — Signature
- scene: Logo A + ALTREX wordmark + slogan "L'IA qui reste chez toi." + chip "Android · hors ligne".
- duration: 6.5s
- transition_in: blur-crossfade 0.6s
- voiceover: "Altrex. L'IA qui reste chez toi."
- src: compositions/frames/09-signature.html
- blueprint: compose
- focal: logo-A + slogan
- sfx: final chord 0.8s

Scene 1 (0.0–1.2s): A glyph fades/scales in (0.92→1), wordmark rises on "Altrex".
→ Scene 2 (1.9–3.2s): slogan "L'IA qui reste chez toi." rises, "chez toi" in blue gradient.
→ Scene 3 (3.4–6.5s): chip "Android · 100 % hors ligne" fades up; slow push-in; final settle and fade to canvas in the last 0.6s.
