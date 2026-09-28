# YouTube Chat Bridge (`ytchat`)

Client-side-only Forge mod for Minecraft **1.20.1 / Forge 47.4.13** (matches TFG-Modern).
Prints a YouTube livestream's chat into your in-game chat HUD. YouTube → Minecraft only.

**No accounts, no API keys, no setup for anyone** — streamer or viewer. The mod reads the
same public live-chat data your browser does.

## Use

1. Drop the jar into your instance's `mods/` folder and launch once (creates the config).
2. `/ytchat set channel @YourHandle` — once. (`channel/UC...`, `c/...`, `user/...` also work.)
   The mod reminds you on every login until this is set.
3. Go live, then `/ytchat start` — finds your current stream and prints chat in game.

More:

- `/ytchat start <videoId|URL|@handle>` — watch something specific without changing the config.
- `/ytchat stop` / `/ytchat status`.
- Polling stops automatically when you leave the world/server.

## Config (`config/ytchat-client.toml`)

`channel`, `prefix`, `showSuperChat`, `showMemberships`, `minPollSeconds`.

## Compatibility

- `clientSideOnly=true`: install on the client only; servers never need it and won't flag it.
- No mixins/coremods — nothing to conflict with TFG-Modern's ~264 mods.
- Honest limitation: this reads YouTube's public web chat instead of the official API, so a
  YouTube page redesign can break it until the mod updates. Failures show up as plain-language
  messages in chat (never a crash), and polling auto-recovers from transient errors.

## Build

Needs JDK 17:

```bash
export JAVA_HOME=~/jdk/jdk-17.0.20.1+1
./gradlew build
```

Jar lands in `build/libs/`.
