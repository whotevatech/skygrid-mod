# WT Sky Grid

Project instructions live in **AGENTS.md** in this directory. Read it first.

To make Gemini CLI pick AGENTS.md up automatically, add to your settings.json:

    { "context": { "fileName": ["AGENTS.md", "GEMINI.md"] } }

## Where you are most useful here

- Reading `MIGRATION-NOTES.md` plus both branches at once to answer "does this symbol exist on 1.21.11".
- Crash-log triage: a full `latest.log` plus the mod list in one pass.
- Auditing the pack that consumes this mod (~160 mods, ~300 quests) — far larger than this repo.
