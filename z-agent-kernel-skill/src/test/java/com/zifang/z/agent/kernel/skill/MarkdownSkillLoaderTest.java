package com.zifang.z.agent.kernel.skill;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MarkdownSkillLoaderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final MarkdownSkillLoader loader = new MarkdownSkillLoader();
    private File root;

    @Before
    public void setUp() throws IOException {
        root = tmp.newFolder("skills");
    }

    private void writeSkill(String category, String name, String content) throws IOException {
        File dir = new File(root, category + "/" + name);
        dir.mkdirs();
        PrintWriter w = new PrintWriter(new File(dir, "SKILL.md"), "UTF-8");
        w.print(content);
        w.close();
    }

    @Test
    public void parseFrontmatterAndBody() throws IOException {
        writeSkill("software-development", "plan",
                "---\n"
                + "name: plan\n"
                + "description: 先列计划再动手\n"
                + "version: 1.2.0\n"
                + "metadata.tags: dev,planning\n"
                + "---\n"
                + "# 计划模式\n\n把计划写进 .zbot/plans/.");
        SkillDocument d = loader.parse(new File(root, "software-development/plan/SKILL.md").toPath());
        assertEquals("plan", d.getName());
        assertEquals("先列计划再动手", d.getDescription());
        assertEquals("1.2.0", d.getVersion());
        assertEquals("dev,planning", d.getMetadata().get("metadata.tags"));
        assertTrue(d.getBody().startsWith("# 计划模式"));
        String prompt = d.toPrompt();
        assertTrue(prompt.contains("## skill: plan (v1.2.0)"));
        assertTrue(prompt.contains("先列计划再动手"));
        assertTrue(prompt.contains("# 计划模式"));
    }

    @Test
    public void parseWithoutFrontmatterUsesDirName() throws IOException {
        writeSkill("misc", "notes", "# 纯正文\n没有 frontmatter");
        SkillDocument d = loader.parse(new File(root, "misc/notes/SKILL.md").toPath());
        assertEquals("notes", d.getName());
        assertEquals("", d.getDescription());
        assertNull(d.getVersion());
        assertTrue(d.getBody().startsWith("# 纯正文"));
    }

    @Test
    public void loadScansTwoLevelTree() throws IOException {
        writeSkill("a", "s1", "---\nname: s1\ndescription: first\n---\nbody1");
        writeSkill("b", "s2", "---\nname: s2\ndescription: second\n---\nbody2");
        List<SkillDocument> all = loader.load(root.toPath());
        assertEquals(2, all.size());
        // sorted path: a/s1 在 b/s2 前
        assertEquals("s1", all.get(0).getName());
        assertEquals("s2", all.get(1).getName());
    }

    @Test
    public void loadMissingRootGivesEmpty() throws IOException {
        assertTrue(loader.load(new File(root, "nope").toPath()).isEmpty());
        assertTrue(loader.load(null).isEmpty());
    }
}
