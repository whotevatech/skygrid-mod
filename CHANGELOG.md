# Changelog

All notable changes to WT Sky Grid are documented in this file.

## [2.4.0] — Minecraft 1.21.1 (Fabric & NeoForge)

### Highlights
- Full redesign of the in-game block editor (`/skygridgui` / ModMenu / Mods config)
- Fabric and NeoForge now share the same editor UI

### Editor
- Centered single-column layout with consistent spacing and control sizes
- Equal **Overworld / Nether / End** tabs
- Search + **Listed / All blocks** filter on one row
- Clearer block rows: checkbox, icon, name/id, then **Add** or `−` weight `+`
- Shift-click weight steps by 10 (range 1–1000)
- Removing a listed block asks for **Remove** first (click again to cancel)
- Tags and unknown ids shown in their own section at the top
- Status line under search: saves immediately, notes **new chunks only**, green on success / red on failure
- Blacklist mode shown in amber; weight controls hidden (listed blocks are exclusions)
- Equal-width Discord / CurseForge / GitHub links

### Reload
- **Reload** confirms, then reads all three JSON files
- If any file is missing or invalid, nothing is changed
- `/skygrid reload` follows the same all-or-nothing rule and reminds you that edits only affect new chunks

### Build
- Gradle wrapper `Main-Class` restored (fixes CI dying before compile)

### Unchanged
- World generation, pool rules, and `/skygrid` edit commands (aside from safer reload)
