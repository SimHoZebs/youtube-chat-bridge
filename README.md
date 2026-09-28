# YouTube Chat Bridge

Client-side-only Forge mod for Minecraft **1.20.1** that prints your YouTube livestream chat
into your in-game chat HUD. YouTube → Minecraft, one direction.

Built against Forge **47.4.13** to match the TFG-Modern modpack. Install on the client only —
servers neither need it nor flag it (`clientSideOnly=true`), and there are no mixins or
coremods to conflict with a large pack.

No accounts, no API keys, no Google Cloud project — for the streamer or anyone using the mod.
It reads the same public live-chat data a browser does.

## Install

1. Download `ytchat-1.0.0.jar` from [Releases](https://github.com/SimHoZebs/youtube-chat-bridge/releases).
2. Drop it into your instance's `mods/` folder and launch once (creates the config).

## Use

1. `/ytchat set channel @YourHandle` — once. `channel/UC...`, `c/...`, `user/...` also work.
   The mod reminds you on every login until this is set.
2. Go live, then `/ytchat start` — finds your current stream and prints chat in game.

More:

- `/ytchat start <videoId|URL|@handle>` — watch something specific without changing the config.
- `/ytchat stop` / `/ytchat status`.
- Polling stops automatically when you leave the world/server.

Chat lines look like `[YT] <author> message`, with `$` highlighting for Super Chats,
`★` for memberships, and `[MOD]` / `[OWNER]` tags.

## Config (`config/ytchat-client.toml`)

`channel`, `prefix`, `showSuperChat`, `showMemberships`, `minPollSeconds`.

## How it works

The mod resolves `<channel>/live` to the active video, reads the public web-client key from
the watch page, then polls YouTube's InnerTube `live_chat/get_live_chat` endpoint following
its continuation/timeout protocol. Everything runs on a background thread; chat output is
posted on the client thread.

## Limitation

This follows YouTube's public page layout instead of the official Data API, so a YouTube
redesign can break chat until the mod updates. Parsing is defensive (unknown nodes are
skipped, never a crash), transient errors auto-recover, and failures show up as
plain-language messages in chat.

## Build from source

Needs JDK 17:

```bash
export JAVA_HOME=<path-to-jdk-17>
./gradlew build   # jar lands in build/libs/
./gradlew test    # unit tests for the chat parsing
```

## License

MIT — see [LICENSE.txt](LICENSE.txt).
