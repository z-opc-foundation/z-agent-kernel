package com.zifang.z.agent.kernel.credential;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CredentialPoolTest {

    @Test
    public void rotationAndCooldown() throws Exception {
        CredentialPool pool = new CredentialPool(Arrays.asList("k1", "k2", "k3"));
        assertEquals(3, pool.size());
        assertEquals("k1", pool.pick());
        pool.reportFailure("k1", 200);
        assertEquals("k2", pool.pick()); // k1 冷却中 → 跳到 k2
        assertTrue(pool.coolingDown("k1"));
        assertEquals(1, pool.failures("k1"));

        pool.reportFailure("k2", 10_000);
        assertEquals("k3", pool.pick());
        pool.reportFailure("k3", 10_000);
        // 全在冷却 → 剩余冷却最短的 k1
        assertEquals("k1", pool.pick());

        Thread.sleep(220); // k1 的 200ms 冷却到期
        assertFalse(pool.coolingDown("k1"));
        assertEquals("k1", pool.pick());

        pool.reportSuccess("k1");
        assertEquals(0, pool.failures("k1"));
    }

    @Test
    public void emptyPoolRejected() {
        try {
            new CredentialPool(java.util.Collections.<String>emptyList());
            org.junit.Assert.fail("should throw");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("at least one"));
        }
    }
}
