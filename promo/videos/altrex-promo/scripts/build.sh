#!/usr/bin/env bash
# Rebuild the Altrex promo composition: frames → index → transitions → voice group → carve.
# Usage: scripts/build.sh [1080x1920|1920x1080]   (run from the project root)
set -euo pipefail
FMT="${1:-1080x1920}"
SK="${HF_SKILLS:-$HOME/.claude/skills}"
node scripts/gen-frames.mjs "$FMT"
sed -i "s/^format: .*/format: $FMT/" STORYBOARD.md
node "$SK/product-launch-video/scripts/captions.mjs" build --storyboard ./STORYBOARD.md --audio-meta ./audio_meta.json --hyperframes . --out ./caption_groups.json
node "$SK/product-launch-video/scripts/assemble-index.mjs" --storyboard ./STORYBOARD.md --hyperframes . --audio-meta ./audio_meta.json
node "$SK/product-launch-video/scripts/transitions.mjs" inject --storyboard ./STORYBOARD.md --hyperframes .
node "$SK/product-launch-video/scripts/transitions.mjs" verify --storyboard ./STORYBOARD.md --index ./index.html
sed -i 's/^\(        id="el-[0-9a-z-]*-voice"\)$/\1\n        data-audio-group="voiceover"/' index.html
sed -i 's/^\(        id="el-captions"\)$/\1\n        data-track-kind="captions"/' index.html
node "$SK/hyperframes-audio/scripts/carve.mjs" --comp ./index.html
