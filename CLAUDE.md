@AGENTS.md

# Claude-specific

- Run the build before reporting a change complete. `./gradlew build` on the current branch.
- When a change affects both branches, do one branch fully — compiled and verified — before starting the other.
- `MIGRATION-NOTES.md` is the API reference for cross-version work. Read it rather than guessing at signatures; the "pre-build guesses" table at the bottom is a record of assumptions that turned out wrong.
