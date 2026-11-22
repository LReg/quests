# Questbook

Text-only quests for a vanilla-feeling Paper server. Spec: the "Questbook – Implementation Spec" artifact. Java 25, Paper 26.2 and 26.3.

## Build

`./gradlew build` produces one jar per Minecraft version in `build/libs/` (`questbook-<version>-mc26.2.jar`, `-mc26.3.jar`). Add a version to `mcVersions` in `build.gradle.kts`. CI (`.github/workflows/build.yml`) builds on every push and PR; a `v*` tag attaches both jars to a GitHub release.

## Use

Players: `/journal`, `/quest answer|abandon|choose|track|mute|unmute`. Editors (`questbook.editor`, granted to everybody by default; take it away with a permissions plugin, `questbook.admin` stays op-only): `/qe` opens the GUI; `/qe create`, `step add`, `sign start` (on an empty sign), `publish` build a quest from commands. Editors only see and edit their own quests; admins (`questbook.admin`) see all and can reassign one with `/qe owner <quest> <player>`. Details are in the spec §11. Config in `plugins/Questbook/config.yml` and `messages.yml`.

## Steps and triggers

Every step has a text told when it starts (before the trigger) and an optional text told when the trigger has fired (`after`, `/qe step after <quest> <n>`). Triggers: `CONTINUE`, `REACH`, `REGION`, `SIGN`, `INTERACT`, `ANSWER`, `DIMENSION`, `ADVANCEMENT` and `KILL` (`/qe step kill <quest> <n> <target> [amount]`, target is `ANY`, `ANIMAL`, `MONSTER`, `PLAYER` or an entity type; the kill count is kept in memory only).

## Branching, choices and endings (1.2)

Every step goes on to the following step, as before. A step's **Then…** menu (in the step menu, bottom row) can change that:

- **Conditions:** "if … → go to step …", checked top to bottom, first true one wins. What to check is picked from a list, never typed: a button of a `CHOICE` step, an outcome of a `WORLD CHOICE` (`FIRST_OF`) step, the answer given at an `ANSWER` step with several answers, an item the player carries (optionally taken), or a choice or ending in another quest. Right-click turns a condition into NOT.
- **Otherwise:** the following step by default; any other step (two paths meet again), or **End the quest** with an optional ending name the player sees in the completion title and the journal.

New triggers: `CHOICE` (2–4 buttons, the click is remembered) and `FIRST_OF` / WORLD CHOICE (2–3 world triggers, whichever happens first counts). **Other text if…** gives a step a different text when a condition is true. Requires can ask for a specific ending (right-click in the Requires menu). The quest menu has a **branch map** (plain lines, `/qe map <quest>` in chat), and Validate warns about steps nothing leads to, loops with no way out and a step that runs on into a step a condition jumps to. `/qe skip [option]` picks a button in test runs.

**Player guide**, per quest (quest menu, compass icon, or `/qe guide <quest> particles|compass <true|false>`; off by default): particles outline where the current step wants the player, and a compass bar at the top of the screen points there. The journal shows the direction, the player's choices, `[Track]` (which quest the compass follows) and, for finished quests, the path they took and their ending.

Summaries keep line breaks and can be 600 characters long; all text limits are in `limits:` in `config.yml`.

**Upgrading from 1.1:** replace the jar. Quest files and the database need no changes: steps get hidden ids (`id: s1`, …) when a quest is next saved, and choices, paths and endings are stored in a new table `progress_extra`, so the old `progress` table is untouched and 1.1 can still read the database. New config keys and messages fall back to the built-in defaults until you add them. Going back to the 1.1 jar also works: quests with conditions run linearly there (unknown keys are logged), and quests with `CHOICE` or `WORLD CHOICE` steps load as drafts.

## Stats export and dashboard

With `export.webhook_url` set in `config.yml`, the plugin POSTs a JSON event for every quest start, completion and abandon (`type`, `quest`, `title`, `player`, `uuid`, `step`, `at`, `server`, plus `ending` on completions with a named ending; the secret goes out as header `X-Questbook-Secret`). The calls are asynchronous and never block the game. Set `export.server_name` per server and run `/qe reload` after changing the config.

The author's setup uses a webhook workflow that stores the events in a table and a dashboard page that reads it. Any HTTP endpoint that accepts this JSON works.
