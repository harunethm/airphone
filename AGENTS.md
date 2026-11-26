# STOP: this is the public repo

If this session was started in `airphone`, tell the user right away that work must happen in `~/Projects/air-phone` (private source of truth), then switch there (`cd ~/Projects/air-phone`, read its `AGENTS.md`) and do the work there.

This repo must never receive test commits, experiments, WIP or fixups. It gets one clean commit per release, made from air-phone via `sync-source.sh` after everything is working and the user is happy. Do not edit, commit or push here otherwise. The release procedure is in `air-phone/AGENTS.md`.
