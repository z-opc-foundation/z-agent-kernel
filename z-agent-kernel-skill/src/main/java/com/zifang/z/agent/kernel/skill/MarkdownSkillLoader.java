package com.zifang.z.agent.kernel.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * SKILL.md 默认加载器 — 0 三方依赖的轻量 frontmatter 解析 (key: value 行).
 *
 * <p>目录约定: {@code <root>/<category>/<skill-name>/SKILL.md}, 递归两层的技能目录都要被扫到.
 * frontmatter: {@code ---} 围栏之间的 {@code key: value} 行 (嵌套写 {@code metadata.tags: a,b}).
 * 正文即首个 {@code ---} 之后的所有内容.
 */
public final class MarkdownSkillLoader implements SkillLoader {

    @Override
    public List<SkillDocument> load(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        List<SkillDocument> out = new ArrayList<SkillDocument>();
        try (Stream<Path> found = Files.walk(root, 4, FileVisitOption.FOLLOW_LINKS)) {
            found.filter(p -> p.getFileName() != null && "SKILL.md".equals(p.getFileName().toString()))
                    .sorted()
                    .forEach(p -> {
                        try {
                            out.add(parse(p));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw e;
        }
        return out;
    }

    @Override
    public SkillDocument parse(Path skillMd) throws IOException {
        String text = new String(Files.readAllBytes(skillMd), StandardCharsets.UTF_8);
        String name = parentDirName(skillMd);
        String description = "";
        String version = null;
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        String body;

        String trimmed = text.trim();
        if (trimmed.startsWith("---")) {
            int end = trimmed.indexOf("\n---", 3);
            if (end >= 0) {
                String fm = trimmed.substring(3, end).trim();
                body = trimmed.substring(end + 4).replaceFirst("^-*\\s*", "");
                for (String line : fm.split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String k = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String v = line.substring(colon + 1).trim();
                    if ("name".equals(k) && !v.isEmpty()) {
                        name = v;
                    } else if ("description".equals(k)) {
                        description = v;
                    } else if ("version".equals(k) && !v.isEmpty()) {
                        version = v;
                    } else if (!v.isEmpty()) {
                        metadata.put(k, v);
                    }
                }
            } else {
                body = "";
            }
        } else {
            body = text;
        }
        return new SkillDocument(name, description, version, metadata, body);
    }

    private static String parentDirName(Path p) {
        Path parent = p.getParent();
        return parent == null ? p.toString() : parent.getFileName().toString();
    }
}
