package com.zifang.z.agent.kernel.agent;

import com.zifang.z.agent.kernel.message.Msg;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KernelAgentCoreTest {

    @Test
    public void budgetNormalFlow() {
        IterationBudget b = new IterationBudget(3, 1000);
        assertTrue(b.canCall());
        b.onCall();
        b.recordTokens(100, 50);
        assertTrue(b.canCall());
        b.onCall();
        b.recordTokens(400, 400);
        // 950/1000 仍够
        assertTrue(b.canCall());
        b.onCall();
        // 3 次用满, 还能走一次 grace
        assertTrue(b.canCall());
        b.onCall();
        assertFalse(b.canCall());
        assertEquals(4, b.apiCalls());
        assertEquals(950, b.tokensUsed());
    }

    @Test
    public void budgetTokenCapTriggersGrace() {
        IterationBudget b = new IterationBudget(100, 500);
        b.recordTokens(400, 200); // 600 > 500
        assertTrue(b.canCall()); // grace
        b.onCall();
        assertFalse(b.canCall());
    }

    @Test
    public void budgetChildFraction() {
        IterationBudget b = new IterationBudget(40, 10000);
        IterationBudget child = b.childBudget(0.25);
        assertEquals(10, child.maxIterations());
        assertEquals(2500, child.maxTokens());
        // fraction 被夹在 [0.05, 1]
        assertEquals(10000, b.childBudget(5.0).maxTokens());
        assertEquals(Math.max(1L, (long) Math.floor(10000 * 0.05)), b.childBudget(0.01).maxTokens());
    }

    @Test
    public void interruptCheckpointThrowsAndResets() {
        InterruptFlag f = new InterruptFlag();
        assertFalse(f.isInterrupted());
        f.checkpoint(); // 不抛
        f.request("user hit stop");
        assertTrue(f.isInterrupted());
        assertEquals("user hit stop", f.reason());
        try {
            f.checkpoint();
            fail("should throw");
        } catch (InterruptFlag.AgentInterruptedException e) {
            assertEquals("user hit stop", e.getMessage());
        }
        f.reset();
        assertFalse(f.isInterrupted());
        assertNull(f.reason());
    }

    @Test
    public void steerQueueFifoAndDrain() {
        SteerQueue q = new SteerQueue();
        assertFalse(q.hasPending());
        assertTrue(q.drain().isEmpty());
        q.add("  往左 ");
        q.add(null);
        q.add("   ");
        q.add("右转");
        assertTrue(q.hasPending());
        List<String> out = q.drain();
        assertEquals(Arrays.asList("往左", "右转"), out);
        assertFalse(q.hasPending());
        assertTrue(q.drain().isEmpty());
    }

    @Test
    public void contextChildDerivesBudgetAndDepth() {
        AgentContext root = AgentContext.root(new IterationBudget(40, 10000));
        assertEquals(0, root.depth());
        assertEquals(3, root.maxConcurrentChildren());

        DelegateSpec spec = new DelegateSpec("research x")
                .async()
                .stripTools(new HashSet<String>(Arrays.asList("exec")));
        AgentContext child = root.newChild(spec);
        assertEquals(1, child.depth());
        assertEquals(10, child.budget().maxIterations());
        assertEquals(2500, child.budget().maxTokens());
        assertTrue(child != root);
        // 中断/steer 不共享 (子代理独立运行)
        root.interrupt().request("stop root");
        assertFalse(child.interrupt().isInterrupted());

        AgentContext grand = child.newChild(new DelegateSpec("nested"));
        assertEquals(2, grand.depth());

        try {
            AgentContext deep = grand;
            AgentContext d3 = deep.newChild(new DelegateSpec("d3"));
            d3.newChild(new DelegateSpec("d4"));
            fail("depth limit should hit at 3");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("depth limit"));
        }
    }

    @Test
    public void contextAttributesImmutable() {
        AgentContext c = AgentContext.root(new IterationBudget(2, 100)).with("session", "s1");
        assertEquals("s1", c.attribute("session"));
        AgentContext c2 = c.with("session", "s2");
        assertEquals("s1", c.attribute("session"));
        assertEquals("s2", c2.attribute("session"));
    }

    @Test
    public void contextEngineContract() {
        final List<Msg> compressed = new ArrayList<Msg>();
        ContextEngine engine = new ContextEngine() {
            long tokens;
            public void onSessionStart() { tokens = 0; }
            public void update(int in, int out) { tokens += in + out; }
            public boolean shouldCompress() { return tokens > 100; }
            public List<Msg> compress(List<Msg> history, Summarizer s) {
                compressed.add(Msg.system("[summary] " + s.summarize(history)));
                return compressed;
            }
        };
        engine.onSessionStart();
        assertFalse(engine.shouldCompress());
        engine.update(60, 60);
        assertTrue(engine.shouldCompress());
        List<Msg> out = engine.compress(
                Arrays.asList(Msg.user("a"), Msg.assistant("b")),
                new ContextEngine.Summarizer() {
                    public String summarize(List<Msg> middle) { return middle.size() + " 条"; }
                });
        assertEquals("[summary] 2 条", out.get(0).getContent());
    }
}
