# Questbook

Text-only quests for a vanilla-feeling Paper server. Spec: the "Questbook – Implementation Spec" artifact. Java 25, Paper 26.2 and 26.3.

## Build

`./gradlew build` produces one jar per Minecraft version in `build/libs/` (`questbook-<version>-mc26.2.jar`, `-mc26.3.jar`). Add a version to `mcVersions` in `build.gradle.kts`. CI (`.github/workflows/build.yml`) builds on every push and PR; a `v*` tag attaches both jars to a GitHub release.

## Use

Players: `/journal`, `/quest answer|abandon|mute|unmute`. Editors (`questbook.editor`): `/qe` opens the GUI; `/qe create`, `step add`, `sign start`, `publish` build a quest from commands. Details are in the spec §11. Config in `plugins/Questbook/config.yml` and `messages.yml`.
