package com.ytchat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for the pure InnerTube parsing helpers (no network, no Minecraft).
 * Run with {@code ./gradlew test}.
 */
public class InnerTubeClientTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject textItem(String id, String author, String text) {
        JsonObject r = new JsonObject();
        r.addProperty("id", id);
        JsonObject a = new JsonObject();
        a.addProperty("simpleText", author);
        r.add("authorName", a);
        JsonObject m = new JsonObject();
        m.addProperty("simpleText", text);
        r.add("message", m);
        return r;
    }

    private static JsonObject wrapAddItem(JsonObject renderer, String key) {
        JsonObject item = new JsonObject();
        item.add(key, renderer);
        JsonObject add = new JsonObject();
        add.add("item", item);
        JsonObject action = new JsonObject();
        action.add("addChatItemAction", add);
        return action;
    }

    @Test
    public void parsesTextSuperchatMembershipAndSkipsJunk() {
        JsonObject text = textItem("m1", "alice", "hello world");
        JsonObject paid = textItem("m2", "bob", "take my money");
        JsonObject amount = new JsonObject();
        amount.addProperty("simpleText", "$5.00");
        paid.add("purchaseAmountText", amount);
        JsonObject member = textItem("m3", "carol", "");
        JsonObject sub = new JsonObject();
        sub.addProperty("simpleText", "Welcome to the club!");
        member.add("headerSubtext", sub);

        JsonArray actions = new JsonArray();
        actions.add(wrapAddItem(text, "liveChatTextMessageRenderer"));
        actions.add(wrapAddItem(paid, "liveChatPaidMessageRenderer"));
        actions.add(wrapAddItem(member, "liveChatMembershipItemRenderer"));
        // Unknown/banner-style actions must be skipped, not crash.
        JsonObject banner = new JsonObject();
        banner.add("addBannerToLiveChatCommand", new JsonObject());
        actions.add(banner);
        JsonObject tombstone = new JsonObject();
        tombstone.add("replaceChatItemAction", new JsonObject());
        actions.add(tombstone);

        List<InnerTubeClient.ChatMessage> out = InnerTubeClient.parseActions(actions);
        assertEquals(3, out.size());

        assertEquals("alice", out.get(0).author());
        assertEquals("hello world", out.get(0).text());
        assertFalse(out.get(0).superChat());

        assertTrue(out.get(1).superChat());
        assertTrue(out.get(1).text().contains("$5.00"));

        assertTrue(out.get(2).membership());
        assertEquals("Welcome to the club!", out.get(2).text());
    }

    @Test
    public void detectsOwnerModeratorMemberBadges() {
        JsonObject r = textItem("m9", "dave", "hi");
        JsonArray badges = new JsonArray();
        for (String tip : new String[]{"Moderator", "Member (3 months)"}) {
            JsonObject badge = new JsonObject();
            badge.addProperty("tooltip", tip);
            JsonObject wrap = new JsonObject();
            wrap.add("liveChatAuthorBadgeRenderer", badge);
            badges.add(wrap);
        }
        r.add("authorBadges", badges);

        JsonArray actions = new JsonArray();
        actions.add(wrapAddItem(r, "liveChatTextMessageRenderer"));
        InnerTubeClient.ChatMessage m = InnerTubeClient.parseActions(actions).get(0);
        assertTrue(m.moderator());
        assertTrue(m.sponsor());
        assertFalse(m.owner());
    }

    @Test
    public void findsContinuationUnderRenamedWrappers() {
        JsonObject resp = obj("{\"continuationContents\":{\"liveChatContinuation\":{"
                + "\"continuations\":[{\"timedContinuationData\":{\"continuation\":\"TOKEN123\",\"timeoutMs\":8000}}],"
                + "\"actions\":[]}}}");
        assertEquals("TOKEN123", InnerTubeClient.findLiveChatContinuation(resp));

        JsonObject next = obj("{\"contents\":{\"twoColumnWatchNextResults\":{\"conversationBar\":{"
                + "\"liveChatRenderer\":{\"header\":{},"
                + "\"continuations\":[{\"reloadContinuationData\":{\"continuation\":\"INIT456\"}}]}}}}}");
        assertEquals("INIT456", InnerTubeClient.findLiveChatContinuation(next));
        assertNull(InnerTubeClient.findLiveChatContinuation(obj("{\"foo\":1}")));
    }

    @Test
    public void runsToTextJoinsRuns() {
        JsonObject node = obj("{\"runs\":[{\"text\":\"a\"},{\"text\":\"b\",\"bold\":true},{\"nope\":1}]}");
        assertEquals("ab", InnerTubeClient.runsToText(node));
        assertEquals("solo", InnerTubeClient.runsToText(obj("{\"simpleText\":\"solo\"}")));
        assertEquals("", InnerTubeClient.runsToText(null));
    }

    @Test
    public void extractVideoIdHandlesCommonForms() {
        assertEquals("dQw4w9WgXcQ", InnerTubeClient.extractVideoId("dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ",
                InnerTubeClient.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s"));
        assertEquals("dQw4w9WgXcQ", InnerTubeClient.extractVideoId("https://youtu.be/dQw4w9WgXcQ"));
        assertNull(InnerTubeClient.extractVideoId("@somehandle"));
        assertNull(InnerTubeClient.extractVideoId(""));
    }
}
