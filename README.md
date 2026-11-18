# Questbook

Text-only quests for a vanilla-feeling Paper server. Spec: the "Questbook – Implementation Spec" artifact. Java 25, Paper 26.2 and 26.3.

## Build

`./gradlew build` produces one jar per Minecraft version in `build/libs/` (`questbook-<version>-mc26.2.jar`, `-mc26.3.jar`). Add a version to `mcVersions` in `build.gradle.kts`. CI (`.github/workflows/build.yml`) builds on every push and PR; a `v*` tag attaches both jars to a GitHub release.

## Use

Players: `/journal`, `/quest answer|abandon|mute|unmute`. Editors (`questbook.editor`): `/qe` opens the GUI; `/qe create`, `step add`, `sign start`, `publish` build a quest from commands. Details are in the spec §11. Config in `plugins/Questbook/config.yml` and `messages.yml`.

## Steps and triggers

Every step has a text told when it starts (before the trigger) and an optional text told when the trigger has fired (`after`, `/qe step after <quest> <n>`). Triggers: `CONTINUE`, `REACH`, `REGION`, `SIGN`, `INTERACT`, `ANSWER`, `DIMENSION`, `ADVANCEMENT` and `KILL` (`/qe step kill <quest> <n> <target> [amount]`, target is `ANY`, `ANIMAL`, `MONSTER`, `PLAYER` or an entity type; the kill count is kept in memory only).

## Stats export and dashboard

With `export.webhook_url` set in `config.yml`, the plugin POSTs a JSON event for every quest start, completion and abandon (`type`, `quest`, `title`, `player`, `uuid`, `step`, `at`, `server`; the secret goes out as header `X-Questbook-Secret`). The calls are asynchronous and never block the game. Set `export.server_name` per server and run `/qe reload` after changing the config.

The author's setup uses a webhook workflow that stores the events in a table and a dashboard page that reads it. Any HTTP endpoint that accepts this JSON works.
