package com.zifang.z.agent.kernel.state;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class InMemorySessionStoreTest {

    @Test
    public void createAppendListSearchDelete() {
        SessionStore s = new InMemorySessionStore();
        SessionStore.SessionMeta a = new SessionStore.SessionMeta(null, "A 的会话", "cli", "m1", "minimax");
        String aId = s.createSession(a);
        SessionStore.SessionMeta b = new SessionStore.SessionMeta(null, "B", "serve", "m2", "openai");
        String bId = s.createSession(b);

        s.appendMessage(aId, new SessionStore.StoredMessage("user", "帮我查 18180 端口"));
        s.appendMessage(aId, new SessionStore.StoredMessage("assistant", "好的"));
        assertEquals(2, s.messages(aId).size());

        // list 按更新时间倒序: a 刚被 append 过 → a 在前
        List<SessionStore.SessionMeta> list = s.listSessions(10);
        assertEquals(aId, list.get(0).id);

        // update 刷新 b → b 回到最前
        assertTrue(s.updateSession(new SessionStore.SessionMeta(bId, "B 改名", null, null, null)));
        assertEquals(bId, s.listSessions(10).get(0).id);

        // search 命中
        List<SessionStore.SearchHit> hits = s.search("18180", 5);
        assertEquals(1, hits.size());
        assertEquals(aId, hits.get(0).sessionId);
        assertTrue(s.search("不存在的词", 5).isEmpty());

        // delete
        assertTrue(s.deleteSession(aId));
        assertFalse(s.deleteSession(aId));
        assertNull(s.session(aId));
        assertTrue(s.messages(aId).isEmpty());
    }

    @Test
    public void appendToUnknownSessionFails() {
        SessionStore s = new InMemorySessionStore();
        try {
            s.appendMessage("ghost", new SessionStore.StoredMessage("user", "x"));
            org.junit.Assert.fail("should throw");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("ghost"));
        }
    }
}
