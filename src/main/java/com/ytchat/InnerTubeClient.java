package com.ytchat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zero-setup YouTube live chat client.
 *
 * <p>Talks to the same public InnerTube endpoints every browser uses: the web client's
 * public key and client version are read from the watch page, so there is no GCP project,
 * no API key and no login for anyone — streamer or viewer.
 *
 * <p>Tradeoff vs the official Data API: YouTube occasionally changes page layout, which can
 * break chat until the mod updates. All parsing is defensive (missing nodes are skipped,
 * never crash) and failures surface as plain-language chat messages.
 *
 * <p>Blocking calls — always invoke from background threads, never the client thread.
 */
public final class InnerTubeClient {
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final Pattern API_KEY_RE = Pattern.compile("\"INNERTUBE_API_KEY\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CLIENT_VERSION_RE =
            Pattern.compile("\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TITLE_RE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern WATCH_PARAM_RE = Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})");
    private static final Pattern SHORT_URL_RE = Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})");
    private static final Pattern BARE_ID_RE = Pattern.compile("^[a-zA-Z0-9_-]{11}$");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public record PageContext(String apiKey, String clientVersion, String pageTitle) {}

    public record ChatMessage(String id, String author, String text,
                              boolean owner, boolean moderator, boolean sponsor,
                              boolean superChat, boolean membership) {}

    public record ChatPage(List<ChatMessage> messages, String continuation, long pollIntervalMs) {}

    private InnerTubeClient() {}

    // ---------- stream resolution ----------

    /** Extracts a video id from a watch URL, youtu.be URL, or bare 11-char id. Null if none. */
    public static String extractVideoId(String arg) {
        if (arg == null) return null;
        String s = arg.strip();
        Matcher m = WATCH_PARAM_RE.matcher(s);
        if (m.find()) return m.group(1);
        m = SHORT_URL_RE.matcher(s);
        if (m.find()) return m.group(1);
        if (BARE_ID_RE.matcher(s).matches()) return s;
        return null;
    }

    /**
     * Resolves a channel reference ({@code @handle}, {@code channel/UC...}, {@code c/...},
     * {@code user/...}, or full URL) to the currently-live video id via the {@code /live}
     * redirect. Throws when the channel is not live.
     */
    public static String resolveLiveVideoId(String channelRef) throws IOException, InterruptedException {
        String start = channelRef.startsWith("http")
                ? channelRef
                : "https://www.youtube.com/" + channelRef.strip() + "/live";
        String current = start;
        for (int i = 0; i < 6; i++) {
            HttpResponse<String> res = get(current);
            int code = res.statusCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = res.headers().firstValue("location")
                        .orElseThrow(() -> new ChatException("YouTube redirect had no target."));
                current = URI.create(current).resolve(loc).toString();
                continue;
            }
            if (code == 429) {
                throw new ChatException("YouTube is rate-limiting this IP right now — wait a minute and retry.");
            }
            if (code >= 400) {
                throw new ChatException("YouTube returned HTTP " + code + ".");
            }
            if (current.contains("consent.youtube.com") || res.body().contains("consent.youtube.com")) {
                throw new ChatException("YouTube showed a consent page for your region. "
                        + "Pass the video id directly: /ytchat start <videoId>.");
            }
            String vid = extractVideoId(current);
            if (vid != null) return vid;
            // Some setups serve 200 on /live with a canonical watch link instead of redirecting.
            Matcher m = WATCH_PARAM_RE.matcher(res.body());
            if (m.find()) return m.group(1);
            throw new ChatException("Channel is not live right now. Go live first, then /ytchat start.");
        }
        throw new ChatException("Too many redirects while finding the live stream.");
    }

    // ---------- page context ----------

    /** Reads the public web-client key, version and page title from the watch page. */
    public static PageContext fetchPageContext(String videoId) throws IOException, InterruptedException {
        HttpResponse<String> res = get("https://www.youtube.com/watch?v=" + videoId);
        if (res.statusCode() == 429) {
            throw new ChatException("YouTube is rate-limiting this IP right now — wait a minute and retry.");
        }
        if (res.statusCode() >= 400) {
            throw new ChatException("Could not open the stream page (HTTP " + res.statusCode() + ").");
        }
        String html = res.body();
        String key = firstGroup(API_KEY_RE, html);
        String version = firstGroup(CLIENT_VERSION_RE, html);
        if (key == null || version == null) {
            throw new ChatException("Could not read YouTube's page data — YouTube likely changed their layout. "
                    + "Check for a mod update.");
        }
        return new PageContext(key, version, unescapeHtml(firstGroup(TITLE_RE, html)));
    }

    // ---------- chat ----------

    /** First continuation for the video's live chat. Throws when chat is unavailable. */
    public static String fetchInitialContinuation(PageContext ctx, String videoId)
            throws IOException, InterruptedException {
        JsonObject body = webContext(ctx);
        body.addProperty("videoId", videoId);
        JsonObject resp = youtubei("next", ctx, body);
        String cont = findLiveChatContinuation(resp);
        if (cont == null) {
            throw new ChatException("No live chat on this video — it may not be live, "
                    + "chat may be disabled, or it is members-only.");
        }
        return cont;
    }

    /** One poll of live chat. Returns messages plus the continuation/timeout for the next poll. */
    public static ChatPage pollLiveChat(PageContext ctx, String continuation)
            throws IOException, InterruptedException {
        JsonObject body = webContext(ctx);
        body.addProperty("continuation", continuation);
        JsonObject resp = youtubei("live_chat/get_live_chat", ctx, body);
        JsonObject cont = optPath(resp, "continuationContents", "liveChatContinuation");
        List<ChatMessage> messages = new ArrayList<>();
        if (cont != null) {
            JsonArray actions = optArr(cont, "actions");
            if (actions != null) messages = parseActions(actions);
        }
        String next = findLiveChatContinuation(resp);
        long timeout = 5000L;
        if (cont != null) {
            JsonArray continuations = optArr(cont, "continuations");
            if (continuations != null && continuations.size() > 0) {
                JsonObject entry = continuations.get(0).getAsJsonObject();
                for (String key : entry.keySet()) {
                    JsonObject data = optObj(entry, key);
                    if (data != null && data.has("timeoutMs")) {
                        try {
                            timeout = data.get("timeoutMs").getAsLong();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        return new ChatPage(messages, next == null ? "" : next, timeout);
    }

    // ---------- message parsing (pure, unit-tested) ----------

    static List<ChatMessage> parseActions(JsonArray actions) {
        List<ChatMessage> out = new ArrayList<>();
        for (JsonElement e : actions) {
            if (!e.isJsonObject()) continue;
            JsonObject add = optObj(e.getAsJsonObject(), "addChatItemAction");
            if (add == null) continue; // banners, tickers, engagement updates…
            JsonObject item = optObj(add, "item");
            if (item == null) continue;
            ChatMessage m = null;
            if (item.has("liveChatTextMessageRenderer")) {
                m = parseText(item.getAsJsonObject("liveChatTextMessageRenderer"), false, false);
            } else if (item.has("liveChatPaidMessageRenderer")) {
                m = parseText(item.getAsJsonObject("liveChatPaidMessageRenderer"), true, false);
            } else if (item.has("liveChatPaidStickerRenderer")) {
                m = parseSticker(item.getAsJsonObject("liveChatPaidStickerRenderer"));
            } else if (item.has("liveChatMembershipItemRenderer")) {
                m = parseText(item.getAsJsonObject("liveChatMembershipItemRenderer"), false, true);
            } else if (item.has("liveChatSponsorshipsGiftPurchaseAnnouncementRenderer")) {
                m = parseGift(item.getAsJsonObject("liveChatSponsorshipsGiftPurchaseAnnouncementRenderer"));
            } else if (item.has("liveChatSponsorshipsGiftRedemptionAnnouncementRenderer")) {
                m = parseGift(item.getAsJsonObject("liveChatSponsorshipsGiftRedemptionAnnouncementRenderer"));
            }
            if (m != null && !m.text().isBlank()) out.add(m);
        }
        return out;
    }

    private static ChatMessage parseText(JsonObject r, boolean superChat, boolean membership) {
        String author = runsToText(optObj(r, "authorName"));
        String text = runsToText(optObj(r, "message"));
        if (membership) {
            String header = runsToText(optObj(r, "headerSubtext"));
            if (header.isBlank()) header = runsToText(optObj(r, "headerPrimaryText"));
            if (!header.isBlank()) text = header + (text.isBlank() ? "" : " — " + text);
        }
        if (superChat) {
            String amount = runsToText(optObj(r, "purchaseAmountText"));
            if (!amount.isBlank()) text = text + " [" + amount + "]";
            if (text.isBlank()) text = amount;
        }
        boolean owner = false, mod = false, sponsor = false;
        JsonArray badges = optArr(r, "authorBadges");
        if (badges != null) {
            for (JsonElement b : badges) {
                if (!b.isJsonObject()) continue;
                JsonObject renderer = optObj(b.getAsJsonObject(), "liveChatAuthorBadgeRenderer");
                String tip = renderer == null ? "" : opt(renderer, "tooltip");
                if (tip.contains("Owner")) owner = true;
                if (tip.contains("Moderator")) mod = true;
                if (tip.contains("Member")) sponsor = true;
            }
        }
        return new ChatMessage(idOf(r, author, text), author.isBlank() ? "?" : author, text,
                owner, mod, sponsor, superChat, membership);
    }

    private static ChatMessage parseSticker(JsonObject r) {
        String author = runsToText(optObj(r, "authorName"));
        String amount = runsToText(optObj(r, "purchaseAmountText"));
        String text = amount.isBlank() ? "[sticker]" : "[sticker " + amount + "]";
        return new ChatMessage(idOf(r, author, text), author.isBlank() ? "?" : author, text,
                false, false, false, true, false);
    }

    private static ChatMessage parseGift(JsonObject r) {
        String header = runsToText(optObj(r, "headerPrimaryText"));
        if (header.isBlank()) header = runsToText(optObj(r, "headerSubtext"));
        String author = runsToText(optObj(r, "authorName"));
        return new ChatMessage(idOf(r, author, header), author.isBlank() ? "?" : author, header,
                false, false, false, false, true);
    }

    private static String idOf(JsonObject r, String author, String text) {
        String id = opt(r, "id");
        return id.isEmpty() ? author + "\0" + text : id;
    }

    /** Concatenates message/author runs; falls back to simpleText. */
    static String runsToText(JsonObject node) {
        if (node == null) return "";
        if (node.has("simpleText") && !node.get("simpleText").isJsonNull()) {
            return node.get("simpleText").getAsString();
        }
        JsonArray runs = optArr(node, "runs");
        if (runs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : runs) {
            if (e.isJsonObject()) sb.append(opt(e.getAsJsonObject(), "text"));
        }
        return sb.toString();
    }

    /**
     * Finds the live-chat continuation token anywhere under a {@code liveChatRenderer} node,
     * tolerating YouTube's wrapper renames ({@code *ContinuationData}).
     */
    static String findLiveChatContinuation(JsonElement root) {
        return findContinuation(root, false);
    }

    private static String findContinuation(JsonElement e, boolean insideRenderer) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            // Scope the search to live-chat subtrees: the initial continuation lives under
            // liveChatRenderer (next endpoint), polling continuations under liveChatContinuation.
            boolean here = insideRenderer || o.has("liveChatRenderer") || o.has("liveChatContinuation");
            if (here) {
                for (String key : o.keySet()) {
                    if (key.endsWith("ContinuationData")) {
                        JsonObject data = optObj(o, key);
                        if (data != null) {
                            String c = opt(data, "continuation");
                            if (!c.isEmpty()) return c;
                        }
                    }
                }
            }
            for (String key : o.keySet()) {
                String found = findContinuation(o.get(key), here);
                if (found != null) return found;
            }
        } else if (e.isJsonArray()) {
            for (JsonElement v : e.getAsJsonArray()) {
                String found = findContinuation(v, insideRenderer);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ---------- HTTP ----------

    private static JsonObject webContext(PageContext ctx) {
        JsonObject client = new JsonObject();
        client.addProperty("clientName", "WEB");
        client.addProperty("clientVersion", ctx.clientVersion());
        client.addProperty("hl", "en");
        client.addProperty("gl", "US");
        JsonObject context = new JsonObject();
        context.add("client", client);
        JsonObject root = new JsonObject();
        root.add("context", context);
        return root;
    }

    private static JsonObject youtubei(String path, PageContext ctx, JsonObject body)
            throws IOException, InterruptedException {
        String url = "https://www.youtube.com/youtubei/v1/" + path + "?key=" + ctx.apiKey() + "&prettyPrint=false";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject o;
        try {
            o = JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (Exception ex) {
            throw new ChatException("YouTube returned an unreadable response (HTTP " + res.statusCode() + ").");
        }
        if (res.statusCode() == 429) {
            throw new ChatException("YouTube is rate-limiting this IP right now — wait a minute and retry.");
        }
        if (res.statusCode() == 403 || res.statusCode() == 404) {
            throw new ChatEndedException("Live chat is unavailable (HTTP " + res.statusCode() + ") — "
                    + "the stream may have ended.");
        }
        if (res.statusCode() >= 400) {
            throw new ChatException("YouTube error (HTTP " + res.statusCode() + "): " + errorMessage(o));
        }
        if (o.has("error")) {
            throw new ChatException("YouTube error: " + errorMessage(o));
        }
        return o;
    }

    private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String errorMessage(JsonObject o) {
        try {
            JsonObject err = optObj(o, "error");
            if (err != null) {
                String m = opt(err, "message");
                if (!m.isEmpty()) return m.length() > 200 ? m.substring(0, 200) : m;
            }
        } catch (Exception ignored) {
        }
        return "unknown error";
    }

    // ---------- small helpers ----------

    private static String firstGroup(Pattern p, String html) {
        if (html == null) return null;
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String unescapeHtml(String s) {
        if (s == null) return "";
        int i = s.indexOf(" - YouTube");
        if (i > 0) s = s.substring(0, i);
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").strip();
    }

    static String opt(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    static JsonObject optObj(JsonObject o, String key) {
        if (o == null || key == null || !o.has(key) || !o.get(key).isJsonObject()) return null;
        return o.getAsJsonObject(key);
    }

    private static JsonArray optArr(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return null;
        return o.getAsJsonArray(key);
    }

    private static JsonObject optPath(JsonObject o, String... keys) {
        JsonObject cur = o;
        for (String k : keys) {
            cur = optObj(cur, k);
            if (cur == null) return null;
        }
        return cur;
    }

    /** General chat failure (network, rate-limit, layout change). Poller retries, then stops. */
    public static class ChatException extends IOException {
        public ChatException(String msg) {
            super(msg);
        }
    }

    /** Chat is gone (stream ended / made private / chat disabled). Poller stops. */
    public static class ChatEndedException extends ChatException {
        public ChatEndedException(String msg) {
            super(msg);
        }
    }
}
