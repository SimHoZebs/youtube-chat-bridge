package com.ytchat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Background poller: resolves the stream, grabs a chat continuation, then follows
 * YouTube's timeout/continuation protocol. No auth of any kind.
 * Future sibling direction (MC -&gt; YT send) would plug in here as a separate sender.
 */
public final class ChatPoller {
    private static final ChatPoller INSTANCE = new ChatPoller();

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ytchat-poller");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();
    private volatile InnerTubeClient.PageContext ctx;
    private volatile String videoId = "";
    private volatile String streamTitle = "";
    private volatile String continuation = "";
    private volatile long received = 0;

    /** Bounded seen-id set for dedupe across polls. */
    private final Map<String, Boolean> seen = Collections.synchronizedMap(
            new LinkedHashMap<>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > 1000;
                }
            });

    private ChatPoller() {}

    public static ChatPoller instance() {
        return INSTANCE;
    }

    public boolean isRunning() {
        return running.get();
    }

    public String statusLine() {
        if (!running.get()) return "not running";
        String what = streamTitle.isEmpty() ? videoId : "\"" + streamTitle + "\"";
        return "watching " + what + " (" + received + " messages shown)";
    }

    /**
     * Starts watching. The argument may be empty (auto-detect via configured channel),
     * a video id / watch URL, or a channel reference ({@code @handle}, full URL…).
     */
    public synchronized void start(String arg) {
        if (running.get()) {
            ChatPrinter.warn("Already running — " + statusLine() + ". Use /ytchat stop first to switch.");
            return;
        }
        running.set(true);
        seen.clear();
        continuation = "";
        received = 0;
        ChatPrinter.info("Connecting to live chat…");
        final String a = arg == null ? "" : arg.trim();
        executor.execute(() -> resolveAndRun(a));
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        ScheduledFuture<?> f = task.getAndSet(null);
        if (f != null) f.cancel(false);
        videoId = "";
        streamTitle = "";
        continuation = "";
    }

    private void resolveAndRun(String arg) {
        if (!running.get()) return;
        try {
            String vid = InnerTubeClient.extractVideoId(arg);
            if (vid == null && !arg.isEmpty() && !YtChatConfig.hasChannel()) {
                // Treat a non-video argument as a one-off channel reference.
                vid = InnerTubeClient.resolveLiveVideoId(stripLiveSuffix(arg));
            } else if (vid == null) {
                if (!YtChatConfig.hasChannel()) {
                    stop();
                    ChatPrinter.error("No channel set. Run /ytchat set channel @YourHandle once, "
                            + "or pass a video id / URL: /ytchat start <videoId>.");
                    return;
                }
                vid = InnerTubeClient.resolveLiveVideoId(YtChatConfig.CHANNEL.get().trim());
            }
            videoId = vid;
            ctx = InnerTubeClient.fetchPageContext(vid);
            streamTitle = ctx.pageTitle().isEmpty() ? vid : ctx.pageTitle();
            continuation = InnerTubeClient.fetchInitialContinuation(ctx, vid);
            ChatPrinter.success("Connected to chat for \"" + streamTitle + "\".");
            scheduleNext(1000L);
        } catch (InnerTubeClient.ChatException e) {
            stop();
            YtChatMod.LOGGER.warn("[ytchat] Could not start: {}", e.toString());
            ChatPrinter.error(e.getMessage());
        } catch (Exception e) {
            stop();
            YtChatMod.LOGGER.warn("[ytchat] Could not start", e);
            ChatPrinter.error("Could not connect to live chat: " + e.getMessage());
        }
    }

    private static String stripLiveSuffix(String s) {
        return s.endsWith("/live") ? s.substring(0, s.length() - "/live".length()) : s;
    }

    private void scheduleNext(long delayMs) {
        if (!running.get()) return;
        ScheduledFuture<?> f = executor.schedule(this::pollOnce, Math.max(500L, delayMs), TimeUnit.MILLISECONDS);
        task.set(f);
    }

    private void pollOnce() {
        if (!running.get()) return;
        try {
            pollAndPrint();
        } catch (Exception first) {
            // One transparent recovery attempt: continuations can expire; re-init and retry once.
            try {
                YtChatMod.LOGGER.info("[ytchat] Poll failed, re-initializing chat ({}).", first.toString());
                continuation = InnerTubeClient.fetchInitialContinuation(ctx, videoId);
                pollAndPrint();
            } catch (InnerTubeClient.ChatEndedException e) {
                stop();
                ChatPrinter.warn("Live chat ended. Polling stopped.");
            } catch (Exception second) {
                if (second instanceof InnerTubeClient.ChatEndedException) {
                    stop();
                    ChatPrinter.warn("Live chat ended. Polling stopped.");
                } else {
                    YtChatMod.LOGGER.warn("[ytchat] Poll failed, retrying in 15s: {}", second.toString());
                    scheduleNext(15_000L);
                }
            }
        }
    }

    private void pollAndPrint() throws Exception {
        InnerTubeClient.ChatPage page = InnerTubeClient.pollLiveChat(ctx, continuation);
        if (!page.continuation().isEmpty()) continuation = page.continuation();
        for (InnerTubeClient.ChatMessage m : page.messages()) {
            if (seen.put(m.id(), Boolean.TRUE) != null) continue;
            if (m.membership()) {
                if (YtChatConfig.SHOW_MEMBERSHIPS.get()) ChatPrinter.membership(m);
                else continue;
            } else if (m.superChat()) {
                if (YtChatConfig.SHOW_SUPERCHAT.get()) ChatPrinter.chat(m);
                else continue;
            } else {
                ChatPrinter.chat(m);
            }
            received++;
        }
        long floor = YtChatConfig.MIN_POLL_SECONDS.get() * 1000L;
        scheduleNext(Math.max(page.pollIntervalMs(), floor));
    }
}
