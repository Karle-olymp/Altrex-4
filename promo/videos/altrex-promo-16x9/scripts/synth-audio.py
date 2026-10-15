#!/usr/bin/env python3
"""synth-audio.py — music bed and sound effects for the Altrex promo, synthesised from scratch
(no sample library, no licence). Writes assets/bgm/altrex-bed.wav and assets/sfx/*.wav, and
records them in audio_meta.json as host audio.

Usage: python3 scripts/synth-audio.py [total_seconds]
Frame starts are read from STORYBOARD.md durations so the cues follow the cut.
"""
import json, re, sys
import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, sosfilt

SR = 48000
rng = np.random.default_rng(7)  # seeded: identical output on every run


def frames_from_storyboard():
    md = open("STORYBOARD.md").read()
    durs = [float(m) for m in re.findall(r"^- duration:\s*([\d.]+)s", md, re.M)]
    starts, t = [], 0.0
    for d in durs:
        starts.append(t)
        t += d
    return starts, durs, t


STARTS, DURS, TOTAL = frames_from_storyboard()
N = int(TOTAL * SR)


def midi(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def lp(x, f, order=2):
    return sosfilt(butter(order, f, "low", fs=SR, output="sos"), x)


def hp(x, f, order=2):
    return sosfilt(butter(order, f, "high", fs=SR, output="sos"), x)


def bp(x, lo, hi, order=2):
    return sosfilt(butter(order, [lo, hi], "band", fs=SR, output="sos"), x)


def env_adsr(n, a, r):
    e = np.ones(n)
    na, nr = int(a * SR), int(r * SR)
    if na:
        e[:na] = np.linspace(0, 1, na) ** 1.5
    if nr:
        e[-nr:] *= np.linspace(1, 0, nr) ** 1.5
    return e


def place(buf, sig, t, gain=1.0):
    i = int(t * SR)
    if i >= len(buf):
        return
    j = min(len(buf), i + len(sig))
    buf[i:j] += sig[: j - i] * gain


def saw_soft(freq, n, harmonics=10, detune=0.0):
    t = np.arange(n) / SR
    out = np.zeros(n)
    for k in range(1, harmonics + 1):
        out += np.sin(2 * np.pi * freq * (1 + detune) * k * t + k * 0.3) / k
    return out


# ── music ────────────────────────────────────────────────────────────────────
BPM = 96
BEAT = 60 / BPM
BAR = 4 * BEAT
# D major colour: Dmaj9 → Bm9 → Gmaj9 → Asus
CHORDS = [
    [50, 57, 61, 64, 66],  # D A C# E F#
    [47, 54, 57, 61, 62],  # B F# A C# D
    [43, 50, 54, 57, 59],  # G D F# A B
    [45, 52, 57, 59, 62],  # A E A B D
]

pad = np.zeros(N)
bass = np.zeros(N)
pluck = np.zeros(N)
bar_count = int(np.ceil(TOTAL / BAR)) + 1
for b in range(bar_count):
    t0 = b * BAR
    chord = CHORDS[b % 4]
    n = int((BAR + 1.2) * SR)
    seg = np.zeros(n)
    for m in chord:
        f = midi(m + 12)
        seg += saw_soft(f, n, 8, 0.0025) + saw_soft(f, n, 8, -0.0025)
    seg = lp(seg, 1400) * env_adsr(n, 1.0, 1.2)
    place(pad, seg, t0, 0.05)
    # sub bass: root, two notes per bar
    for k in range(2):
        nb = int(BAR / 2 * SR)
        tt = np.arange(nb) / SR
        f = midi(chord[0] - 12)
        s = np.sin(2 * np.pi * f * tt) + 0.25 * np.sin(4 * np.pi * f * tt)
        s *= np.minimum(1, tt / 0.02) * np.exp(-tt * 1.4)
        place(bass, s, t0 + k * BAR / 2, 0.32)
    # pluck arpeggio, eighth notes
    pattern = [0, 2, 4, 3, 1, 3, 4, 2]
    for k, idx in enumerate(pattern):
        f = midi(chord[idx] + 24)
        npk = int(0.9 * SR)
        tt = np.arange(npk) / SR
        s = (np.sin(2 * np.pi * f * tt) + 0.35 * np.sin(4 * np.pi * f * tt + 0.5) + 0.12 * np.sin(6 * np.pi * f * tt))
        s *= np.minimum(1, tt / 0.004) * np.exp(-tt * 6.5)
        place(pluck, s, t0 + k * BEAT / 2, 0.11 * (1.0 if k % 2 == 0 else 0.75))

pluck = lp(pluck, 5200)
# stereo-ish delay on plucks
delay = np.zeros(N)
d = int(BEAT * 0.75 * SR)
delay[d:] = pluck[:-d] * 0.35

t = np.arange(N) / SR


def ramp(points):
    """piecewise-linear automation from [(time, value), ...]"""
    xs, ys = zip(*points)
    return np.interp(t, xs, ys)


f1, f2, f3, f4 = STARTS[0], STARTS[1], STARTS[2], STARTS[3]
f9 = STARTS[8]
# Hook: soft air + pad, everything drops when airplane mode turns on (≈1.0 s),
# returns as the answer appears (≈3.7 s); the full groove starts with the logo.
pad_auto = ramp([(0, 0.0), (0.4, 0.55), (0.95, 0.55), (1.15, 0.08), (3.6, 0.08), (4.4, 0.7), (f2, 0.9),
                 (f9 + 0.8, 1.0), (TOTAL - 2.6, 0.9), (TOTAL, 0.0)])
pluck_auto = ramp([(0, 0), (f2 + 1.6, 0), (f2 + 2.4, 0.55), (f3, 0.8), (f4, 1.0), (f9, 0.9), (f9 + 2.5, 0.35),
                   (TOTAL - 1.0, 0.0), (TOTAL, 0)])
bass_auto = ramp([(0, 0), (f3 - 0.1, 0), (f3 + 0.6, 0.8), (f4, 1.0), (f9 + 1.5, 0.7), (TOTAL - 1.0, 0), (TOTAL, 0)])

air = hp(rng.standard_normal(N), 6000) * 0.006 * ramp([(0, 0), (0.3, 1), (0.95, 1), (1.05, 0), (TOTAL, 0)])

music = pad * pad_auto + (pluck + delay) * pluck_auto + bass * bass_auto + air

# logo swell (sub + reversed noise) into frame 2, and the final chord on the signature
nsw = int(1.6 * SR)
tt = np.arange(nsw) / SR
swell = np.sin(2 * np.pi * 41.2 * tt) * (tt / 1.6) ** 2 * 0.5
swell += lp(rng.standard_normal(nsw), 1800) * (tt / 1.6) ** 3 * 0.05
place(music, swell, f2 - 1.2, 1.0)

nch = int(5.0 * SR)
tt = np.arange(nch) / SR
chord_final = np.zeros(nch)
for m in [38, 50, 57, 61, 64, 69, 73]:
    chord_final += saw_soft(midi(m), nch, 6) * (0.6 if m < 45 else 0.35)
chord_final = lp(chord_final, 2200) * np.minimum(1, tt / 0.08) * np.exp(-tt * 0.55) * 0.09
place(music, chord_final, f9 + 0.82, 1.0)

music = np.tanh(music * 1.4) / 1.4
peak = np.max(np.abs(music))
music = music / peak * 0.7

# stereo: tiny Haas offset on plucks/delay for width
left = music
right = np.roll(music, int(0.0009 * SR))
bed = np.stack([left, right], axis=1)
wavfile.write("assets/bgm/altrex-bed.wav", SR, (bed * 32767).astype(np.int16))

# ── sound effects ────────────────────────────────────────────────────────────


def sfx_click():
    n = int(0.09 * SR)
    tt = np.arange(n) / SR
    s = bp(rng.standard_normal(n), 1800, 6000) * np.exp(-tt * 90) * 0.6
    s += np.sin(2 * np.pi * 2300 * tt) * np.exp(-tt * 120) * 0.4
    s += np.sin(2 * np.pi * 900 * tt) * np.exp(-tt * 60) * 0.3
    return s


def sfx_whoosh(dur=0.6, lo=300, hi=3000):
    n = int(dur * SR)
    tt = np.arange(n) / SR
    noise = rng.standard_normal(n)
    out = np.zeros(n)
    steps = 12
    for k in range(steps):
        a, b = k * n // steps, (k + 1) * n // steps
        f = lo * (hi / lo) ** (k / (steps - 1))
        out[a:b] = bp(noise, f * 0.7, min(f * 1.4, 20000))[a:b]
    e = np.sin(np.pi * tt / dur) ** 2
    return out * e * 0.5


def sfx_tick(f=1760):
    n = int(0.12 * SR)
    tt = np.arange(n) / SR
    return (np.sin(2 * np.pi * f * tt) + 0.3 * np.sin(4 * np.pi * f * tt)) * np.exp(-tt * 45) * 0.35


def sfx_pop():
    n = int(0.14 * SR)
    tt = np.arange(n) / SR
    f = 520 + 520 * np.minimum(1, tt / 0.03)
    ph = 2 * np.pi * np.cumsum(f) / SR
    return np.sin(ph) * np.exp(-tt * 32) * np.minimum(1, tt / 0.002) * 0.45


def sfx_shimmer():
    n = int(1.4 * SR)
    tt = np.arange(n) / SR
    s = np.zeros(n)
    for k, m in enumerate([81, 85, 88, 93, 97]):
        d = int(k * 0.06 * SR)
        nn = n - d
        t2 = np.arange(nn) / SR
        s[d:] += np.sin(2 * np.pi * midi(m) * t2) * np.exp(-t2 * 3.2) * 0.12
    return s


def sfx_ding():
    n = int(1.2 * SR)
    tt = np.arange(n) / SR
    return (np.sin(2 * np.pi * 1318.5 * tt) * 0.5 + np.sin(2 * np.pi * 1975.5 * tt) * 0.3) * np.exp(-tt * 4.0) * np.minimum(1, tt / 0.003) * 0.4


def sfx_lock():
    n = int(0.25 * SR)
    tt = np.arange(n) / SR
    s = bp(rng.standard_normal(n), 700, 4000) * np.exp(-tt * 70) * 0.5
    s2 = np.sin(2 * np.pi * 180 * tt) * np.exp(-tt * 40) * 0.6
    out = s + s2
    second = np.zeros(n)
    k = int(0.07 * SR)
    second[k:] = (bp(rng.standard_normal(n - k), 1500, 6000) * np.exp(-np.arange(n - k) / SR * 110) * 0.35)
    return out + second


SFX = {
    "click": sfx_click(),
    "whoosh": sfx_whoosh(),
    "whoosh-short": sfx_whoosh(0.4, 500, 4000),
    "tick": sfx_tick(),
    "tick-low": sfx_tick(1318),
    "pop": sfx_pop(),
    "shimmer": sfx_shimmer(),
    "ding": sfx_ding(),
    "lock": sfx_lock(),
}
for name, s in SFX.items():
    s = s / max(1e-9, np.max(np.abs(s))) * 0.8
    wavfile.write(f"assets/sfx/{name}.wav", SR, (s * 32767).astype(np.int16))

# cues: (frame number, sfx name, offset within the frame, volume)
CUES = [
    (1, "click", 0.95, 0.55), (1, "whoosh-short", 2.2, 0.35), (1, "tick-low", 3.25, 0.25), (1, "shimmer", 3.7, 0.4),
    (2, "shimmer", 1.4, 0.45),
    (3, "tick", 0.45, 0.22), (3, "tick", 2.68, 0.22), (3, "tick-low", 4.58, 0.22),
    (4, "whoosh-short", 0.0, 0.25), (4, "pop", 0.68, 0.35), (4, "pop", 1.6, 0.35), (4, "pop", 2.17, 0.35), (4, "tick", 4.78, 0.22),
    (5, "whoosh-short", 1.95, 0.22), (5, "whoosh-short", 2.82, 0.22), (5, "whoosh-short", 3.6, 0.22), (5, "ding", 6.25, 0.4),
    (6, "tick", 3.0, 0.22), (6, "tick", 3.98, 0.22), (6, "tick", 4.58, 0.22), (6, "pop", 5.45, 0.35), (6, "ding", 5.65, 0.3),
    (7, "pop", 0.2, 0.25), (7, "tick", 2.6, 0.2),
    (8, "lock", 2.6, 0.6),
    (9, "whoosh", 1.85, 0.2),
]

meta = json.load(open("audio_meta.json"))
meta["bgm"] = {"path": "assets/bgm/altrex-bed.wav", "source": "host", "volume": 0.5, "duration_s": round(TOTAL, 3)}
meta["sfx"] = [
    {"frame": f, "file": f"assets/sfx/{name}.wav", "offset_s": off, "duration_s": round(len(SFX[name]) / SR, 3),
     "volume": vol, "source": "host"}
    for f, name, off, vol in CUES
]
json.dump(meta, open("audio_meta.json", "w"), ensure_ascii=False, indent=1)
print(f"bed {TOTAL:.1f}s · {len(SFX)} sfx · {len(CUES)} cues")
