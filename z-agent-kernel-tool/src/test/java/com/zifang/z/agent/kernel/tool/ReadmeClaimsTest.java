package com.zifang.z.agent.kernel.tool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * z-agent-kernel README 定量主张自守卫。
 *
 * <p>纪律（与组织内其他仓守卫一致）：
 * <ul>
 *   <li>事实对象只装<b>源码侧</b>事实（pom / 源码 / 配置文件现读）；README 侧的数值一律
 *       <b>在核对当下从 README 现解析</b>，绝不与源码值放进同一个对象互相比（那是恒等式比较）。</li>
 *   <li>主张抠不到就红 —— 解析不出 README 里写的数，不许静默跳过。</li>
 *   <li>只数 {@code src/main} 侧产物；测试侧计数一律用「排除本守卫文件」的口径。</li>
 *   <li>本文件里的被 grep 字面量一律拆两段拼接，免得 README 贴的复算命令把守卫自己数进去。</li>
 * </ul>
 */
public class ReadmeClaimsTest {

    /** 本守卫自己的文件名 —— 复算 @Test 分布时按 README 给的口径排除自己。 */
    private static final String GUARD_FILE = "ReadmeClaimsTest.java";

    // ---------------------------------------------------------------- 基础设施

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 10 && p != null; i++) {
            Path pom = p.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                String t = stripXmlComments(readText(pom));
                // 必须同时是聚合根：artifactId 命中 + packaging=pom + 真的带 <module>
                // （子模块 pom 的 <parent> 里也有 <artifactId>z-agent-kernel</artifactId>，不能只看 artifactId）
                boolean isRoot = t.contains("<artifactId>z-agent-kernel</artifactId>")
                        && t.contains("<packaging>pom</packaging>")
                        && Pattern.compile("<module>").matcher(t).find();
                if (isRoot) {
                    return p;
                }
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未找到 z-agent-kernel 仓根：从 " + Paths.get("").toAbsolutePath() + " 向上找不到含 <artifactId>z-agent-kernel</artifactId> 的 pom.xml");
    }

    private static String stripXmlComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "");
    }

    private static String readText(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读文件失败：" + p, e);
        }
    }

    /** README 全文（每次现读，README 侧数值必须现解析）。 */
    private static String readme() {
        return readText(repoRoot().resolve("README.md"));
    }

    /**
     * 取 marker 所在<b>标题</b>的小节正文（到下一个标题行为止）。
     * marker 只用标题里的 ASCII 片段，避免 emoji 字面量受编译编码影响。
     * 围栏代码块内的 '#' 开头行（项目结构树）不算标题，必须跳过。
     */
    private static String section(String headingText) {
        String[] lines = readme().split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        boolean fence = false;
        int level = 99;
        List<String> seen = new ArrayList<String>();
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("```")) {
                fence = !fence;
            }
            if (!in) {
                if (!fence && t.startsWith("#")) {
                    String text = headingText(t);
                    seen.add(text);
                    if (text.equals(headingText)) {
                        in = true;
                        level = headingLevel(t);
                    }
                }
                continue;
            }
            // 只在「同级或更高级」标题处断开；### 子标题仍属本节
            if (!fence && t.startsWith("#") && headingLevel(t) <= level) {
                break;
            }
            sb.append(line).append('\n');
        }
        if (!in) {
            fail("README 里没有标题文字等于 \"" + headingText + "\" 的小节；现有标题=" + seen);
        }
        return sb.toString();
    }

    private static int headingLevel(String heading) {
        int n = 0;
        while (n < heading.length() && heading.charAt(n) == '#') {
            n++;
        }
        return n;
    }

    /** 去掉行首 #、emoji 与变体选择符，剩下的就是标题文字（emoji 用码点判断，不吃编译编码的亏）。 */
    private static String headingText(String raw) {
        String t = raw.trim();
        while (t.startsWith("#")) {
            t = t.substring(1);
        }
        t = t.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            int cp = t.codePointAt(i);
            if (cp == 0xFE0F || cp == 0x200D
                    || (cp >= 0x1F300 && cp <= 0x1FAFF)
                    || (cp >= 0x2600 && cp <= 0x27BF)) {
                if (cp > 0xFFFF) {
                    i++;
                }
                continue;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString().trim();
    }

    /** 取 README 表格中首格等于 field 的那一行的第 col 格（col 从 0 起，0 是字段名本身）。 */
    private static String tableCell(String field, int col) {
        for (String line : readme().split("\n", -1)) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                continue;
            }
            String[] cells = splitRow(t);
            if (cells.length > col && cells[col].equals(field)) {
                return cells[col + 1];
            }
        }
        fail("README 表格里找不到字段行：" + field);
        return null;
    }

    private static String[] splitRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|")) {
            t = t.substring(0, t.length() - 1);
        }
        return Arrays.stream(t.split("\\|", -1)).map(String::trim).toArray(String[]::new);
    }

    /** 在 text 里抠出第一个匹配的整数（抠不到就红）。 */
    private static int intClaim(String text, Pattern p, String what) {
        Matcher m = p.matcher(text);
        if (!m.find()) {
            fail("README 主张抠不到数值：" + what + "（模式 " + p.pattern() + "）");
        }
        return Integer.parseInt(m.group(1));
    }

    private static List<String> codeSpans(String cell) {
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("`([^`]+)`").matcher(cell);
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return out;
    }

    // ---------------------------------------------------------------- 源码侧事实

    private static String rootPom() {
        return stripXmlComments(readText(repoRoot().resolve("pom.xml")));
    }

    private static List<String> pomModules() {
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("<module>(.*?)</module>", Pattern.DOTALL).matcher(rootPom());
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return out;
    }

    private static String rootProperty(String name) {
        Matcher m = Pattern.compile("<" + Pattern.quote(name) + ">(.*?)</" + Pattern.quote(name) + ">", Pattern.DOTALL)
                .matcher(rootPom());
        if (!m.find()) {
            fail("根 pom 里找不到属性 " + name);
        }
        return m.group(1).trim();
    }

    /** 根 pom dependencyManagement 里的条目：[groupId, artifactId, version]。 */
    private static List<String[]> rootManagedDeps() {
        String pom = rootPom();
        Matcher dm = Pattern.compile("<dependencyManagement>(.*?)</dependencyManagement>", Pattern.DOTALL).matcher(pom);
        List<String[]> out = new ArrayList<String[]>();
        if (!dm.find()) {
            return out;
        }
        Matcher dep = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(dm.group(1));
        while (dep.find()) {
            String body = dep.group(1);
            out.add(new String[] {
                    tag(body, "groupId"), tag(body, "artifactId"), tag(body, "version")
            });
        }
        return out;
    }

    /** 根 pom 顶层（不含 dependencyManagement 段）的 dependency 条目。 */
    private static List<String[]> rootTopDeps() {
        String pom = rootPom().replaceAll("(?s)<dependencyManagement>.*?</dependencyManagement>", "");
        List<String[]> out = new ArrayList<String[]>();
        Matcher dep = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(pom);
        while (dep.find()) {
            String body = dep.group(1);
            out.add(new String[] {tag(body, "groupId"), tag(body, "artifactId"), tag(body, "scope")});
        }
        return out;
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    private static List<Path> moduleDirs() {
        List<Path> out = new ArrayList<Path>();
        for (Path p : listDirs(repoRoot())) {
            if (p.getFileName().toString().startsWith("z-agent-kernel-")) {
                out.add(p);
            }
        }
        return out;
    }

    private static List<Path> listFiles(Path dir) {
        List<Path> out = new ArrayList<Path>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                if (Files.isRegularFile(p)) {
                    out.add(p);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("列文件失败：" + dir, e);
        }
        return out;
    }

    private static List<Path> listDirs(Path dir) {
        List<Path> out = new ArrayList<Path>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                if (Files.isDirectory(p)) {
                    out.add(p);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("列目录失败：" + dir, e);
        }
        return out;
    }

    /** src/main 下的 .java 文件（只数主产物）。 */
    private static List<Path> mainJavaFiles() {
        List<Path> out = new ArrayList<Path>();
        for (Path m : moduleDirs()) {
            Path src = m.resolve("src/main");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(src)) {
                for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                    if (p.getFileName().toString().endsWith(".java")) {
                        out.add(p);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("扫目录失败：" + src, e);
            }
        }
        return out;
    }

    private static List<Path> testJavaFiles() {
        List<Path> out = new ArrayList<Path>();
        for (Path m : moduleDirs()) {
            Path src = m.resolve("src/test");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(src)) {
                for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                    if (p.getFileName().toString().endsWith(".java")) {
                        out.add(p);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("扫目录失败：" + src, e);
            }
        }
        return out;
    }

    /** wc -l 口径的行数（README 用的就是这个口径）。 */
    private static int wcLines(Path p) {
        String s = readText(p);
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** 各模块的 @Test 支数（排除本守卫文件，与 README 贴的复算命令同口径）。 */
    private static Map<String, Integer> testCountPerModule() {
        Map<String, Integer> out = new TreeMap<String, Integer>();
        for (Path m : moduleDirs()) {
            Path src = m.resolve("src/test");
            int n = 0;
            if (Files.isDirectory(src)) {
            try (Stream<Path> s = Files.walk(src)) {
                for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                    if (!p.getFileName().toString().endsWith(".java") || GUARD_FILE.equals(p.getFileName().toString())) {
                        continue;
                    }
                    String t = readText(p);
                    n += countTestAnnotations(t);
                }
            } catch (IOException e) {
                throw new IllegalStateException("扫目录失败：" + src, e);
            }
            }
            // 没有 src/test 目录的模块，测试数记 0（它就是「零测试模块」）
            out.put(m.getFileName().toString().replace("z-agent-kernel-", ""), n);
        }
        return out;
    }

    /** 数 `@Test` 注解出现次数（用拼接字面量，免得 README 贴的 rg 命令命中守卫自己）。 */
    private static int countTestAnnotations(String text) {
        String needle = "@" + "Test";
        int n = 0;
        int i = text.indexOf(needle);
        while (i >= 0) {
            n++;
            i = text.indexOf(needle, i + needle.length());
        }
        return n;
    }

    /** 主产物类型分布：package-info / interface / class / enum（按「与文件同名的顶层类型」口径）。 */
    private static Map<String, Integer> typeBreakdown() {
        Map<String, Integer> out = new TreeMap<String, Integer>();
        for (String k : new String[] {"package-info", "interface", "class", "enum"}) {
            out.put(k, 0);
        }
        for (Path p : mainJavaFiles()) {
            String fileName = p.getFileName().toString();
            if ("package-info.java".equals(fileName)) {
                out.put("package-info", out.get("package-info") + 1);
                continue;
            }
            String stem = fileName.substring(0, fileName.length() - 5);
            String t = readText(p);
            Matcher m = Pattern.compile(
                    "^\\s*(?:public\\s+|abstract\\s+|final\\s+|sealed\\s+|static\\s+)*(interface|enum|class|record)\\s+"
                            + Pattern.quote(stem) + "\\b", Pattern.MULTILINE).matcher(t);
            if (m.find()) {
                String k = m.group(1);
                if (out.containsKey(k)) {
                    out.put(k, out.get(k) + 1);
                }
            }
        }
        return out;
    }

    /** 只有 package-info.java 的模块。 */
    private static Set<String> packageInfoOnlyModules() {
        Set<String> out = new TreeSet<String>();
        for (Path m : moduleDirs()) {
            Path src = m.resolve("src/main");
            if (!Files.isDirectory(src)) {
                continue;
            }
            List<Path> js = new ArrayList<Path>();
            try (Stream<Path> s = Files.walk(src)) {
                for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                    if (p.getFileName().toString().endsWith(".java")) {
                        js.add(p);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            if (js.isEmpty()) {
                continue;
            }
            boolean only = true;
            for (Path p : js) {
                if (!"package-info.java".equals(p.getFileName().toString())) {
                    only = false;
                    break;
                }
            }
            if (only) {
                out.add(m.getFileName().toString().replace("z-agent-kernel-", ""));
            }
        }
        return out;
    }

    /** 跨模块 import 边：模块 -> 被依赖模块 -> 次数（不含本模块内部引用）。 */
    private static Map<String, Map<String, Integer>> importEdges() {
        Map<String, Map<String, Integer>> out = new TreeMap<String, Map<String, Integer>>();
        for (Path m : moduleDirs()) {
            Path src = m.resolve("src/main");
            if (!Files.isDirectory(src)) {
                continue;
            }
            Map<String, Integer> edges = new TreeMap<String, Integer>();
            try (Stream<Path> s = Files.walk(src)) {
                for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                    if (!p.getFileName().toString().endsWith(".java")) {
                        continue;
                    }
                    String t = readText(p);
                    Matcher mm = Pattern.compile(
                            "^import\\s+(?:static\\s+)?com\\.zifang\\.z\\.agent\\.kernel\\.([a-z0-9]+)\\.",
                            Pattern.MULTILINE).matcher(t);
                    while (mm.find()) {
                        String target = mm.group(1);
                        if (target.equals(m.getFileName().toString().replace("z-agent-kernel-", ""))) {
                            continue;
                        }
                        Integer cur = edges.get(target);
                        edges.put(target, cur == null ? 1 : cur + 1);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            if (!edges.isEmpty()) {
                out.put(m.getFileName().toString().replace("z-agent-kernel-", ""), edges);
            }
        }
        return out;
    }

    /** 某个 SPI 源文件里声明过的方法名（粗口径：文件里出现过的方法名）。 */
    private static Set<String> declaredMethodNames(Path file) {
        Set<String> out = new TreeSet<String>();
        String t = readText(file);
        Matcher m = Pattern.compile(
                "^\\s*(?:public\\s+|protected\\s+|private\\s+|default\\s+|static\\s+|abstract\\s+|final\\s+|synchronized\\s+)*"
                        + "(?:<[^>]+>\\s*)?[\\w<>,.\\[\\]?\\s]+?\\s+(\\w+)\\s*\\(", Pattern.MULTILINE).matcher(t);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static Path findSource(String module, String typeName) {
        Path base = repoRoot().resolve("z-agent-kernel-" + module).resolve("src/main");
        try (Stream<Path> s = Files.walk(base)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (p.getFileName().toString().equals(typeName + ".java")) {
                    return p;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return null;
    }

    /** 全仓 java 文件里命中某个正则的行数（用于钉「0 命中」类主张）。 */
    private static int grepJavaCount(String regex) {
        int n = 0;
        for (Path p : mainJavaFiles()) {
            Matcher m = Pattern.compile(regex).matcher(readText(p));
            while (m.find()) {
                n++;
            }
        }
        return n;
    }

    /** 全仓 pom（含注释）里命中某个正则的文件数。 */
    private static List<String> pomFilesMentioning(String regex) {
        List<String> out = new ArrayList<String>();
        try (Stream<Path> s = Files.walk(repoRoot())) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                if (!"pom.xml".equals(p.getFileName().toString()) || p.toString().contains("/target/")) {
                    continue;
                }
                if (Pattern.compile(regex).matcher(readText(p)).find()) {
                    out.add(repoRoot().relativize(p).toString());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // ---------------------------------------------------------------- 用例

    /** 基本信息表：版本 / 坐标 / 父项目 / 模块数 必须与根 pom 现读值一致。 */
    @Test
    public void 基本信息表与根pom一致() {
        String versionCell = tableCell("**当前版本**", 0);
        String revision = rootProperty("revision");
        assertTrue(versionCell.contains("`" + revision + "`"),
                "[当前版本] README 写 " + versionCell + "，根 pom <properties><revision> 是 " + revision);

        String coordCell = tableCell("**Maven 坐标**", 0);
        Matcher g = Pattern.compile("`([\\w.\\-]+):z-agent-kernel:\\$\\{revision\\}`").matcher(coordCell);
        assertTrue(g.find(), "[Maven 坐标] README 写 " + coordCell + "，抠不出 groupId:artifactId:${revision}");
        String pomGroup = tag(rootPom(), "groupId");
        assertEquals(pomGroup, g.group(1), "[Maven 坐标] README 的 groupId 与根 pom 不一致");

        String parentCell = tableCell("**父项目**", 0);
        Matcher p = Pattern.compile("z-boot-parent:([\\d.]+)").matcher(parentCell);
        assertTrue(p.find(), "[父项目] README 写 " + parentCell + "，抠不出 z-boot-parent 版本");
        String pom = rootPom();
        Matcher pm = Pattern.compile("<parent>(.*?)</parent>", Pattern.DOTALL).matcher(pom);
        assertTrue(pm.find(), "根 pom 没有 <parent>");
        assertEquals(tag(pm.group(1), "artifactId"), "z-boot-parent", "[父项目] 根 pom 的 parent 不是 z-boot-parent");
        assertEquals(tag(pm.group(1), "version"), p.group(1), "[父项目] README 与根 pom 的 parent 版本不一致");

        // 仓库行：「26 个 `jar` 子模块」
        String repoCell = tableCell("**仓库**", 0);
        int claimed = intClaim(repoCell, Pattern.compile("(\\d+) 个 `jar` 子模块"), "[仓库] 模块数");
        assertEquals(pomModules().size(), claimed, "[仓库] README 写 " + claimed + " 个 jar 子模块，根 pom <modules> 是 " + pomModules().size());
        for (String mod : pomModules()) {
            Path modPom = repoRoot().resolve(mod).resolve("pom.xml");
            assertTrue(Files.isRegularFile(modPom), "[仓库] 根 pom 声明的模块 " + mod + " 在盘上不存在");
            String mp = stripXmlComments(readText(modPom));
            assertEquals("jar", tag(mp, "packaging") == null || tag(mp, "packaging").isEmpty() ? "jar" : tag(mp, "packaging"),
                    "[仓库] 模块 " + mod + " 的 packaging 不是 jar");
        }

        // 运行口径：Java 版本 + Spring 0 命中
        //
        // ⚠ 这里曾经硬断言「README 必须写 Java 8」（2026-10-30 SB3 迁移后失效）：
        //   本仓早已随父链升到 Java 17（class major 61），README 却还写着 Java 8，
        //   而守门人在**守护一个错误事实** ⇒ 假绿。
        //   改成：**实测编译产物的 class major**，README 必须与之一致。
        //   这样口径再变时守门人会立刻判红，而不是陪着 README 一起错。
        String runCell = tableCell("**运行口径**", 0);
        int major = compiledClassMajorVersion();
        String expectedJava = major == 61 ? "Java 17" : (major == 52 ? "Java 8" : "Java " + major);
        assertTrue(runCell.contains(expectedJava),
                "[运行口径] README 未声明实测编译口径 " + expectedJava + "（class major " + major + "）：" + runCell);
        int spring = grepJavaCount("org\\.spring" + "framework");
        assertEquals(0, spring, "[运行口径] README 写 Spring 0 命中，实测 " + spring + " 个文件命中");
        assertTrue(runCell.contains("**0 个文件**"), "[运行口径] README 未写 Spring 0 命中：" + runCell);
    }

    /**
     * 实测本仓编译产物的 class 文件 major version（52 = Java 8，61 = Java 17）。
     *
     * <p>取 {@code target/classes} 下的主源码 class（不是 test-classes），
     * 读它的 class 文件头 8 字节取 major —— 这是**实际编出来的字节码**，
     * 比 README 文字、比 pom 里的属性都权威。
     *
     * @return major version；找不到产物时抛 AssertionError（拿不到真相就不假装通过）
     */
    private static int compiledClassMajorVersion() {
        Path root = repoRoot();
        List<Path> found = new ArrayList<>();
        // 本仓是多模块仓，class 散在各子模块的 target/classes 下 ⇒ 全仓找
        try (Stream<Path> s = Files.walk(root, 12)) {
            s.filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> p.toString().contains("/target/classes/"))
                    .filter(p -> !p.toString().contains("/target/test-classes/"))
                    .forEach(found::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (found.isEmpty()) {
            throw new AssertionError("[运行口径] 找不到编译产物（各模块 target/classes 下无 class），无法实测 class major");
        }
        for (Path p : found) {
            try (java.io.DataInputStream in = new java.io.DataInputStream(Files.newInputStream(p))) {
                int magic = in.readInt();
                if (magic != 0xCAFEBABE) {
                    continue;
                }
                in.readUnsignedShort();      // minor version
                return in.readUnsignedShort(); // major version
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        throw new AssertionError("[运行口径] 编译产物里没有合法的 class 文件，无法实测 class major");
    }

    /** 模块清单：根 pom <modules> 与磁盘目录逐字一致，且 README 点名的模块都存在。 */
    @Test
    public void 模块清单与磁盘逐字一致() {
        List<String> pomMods = pomModules();
        Set<String> disk = new TreeSet<String>();
        for (Path p : moduleDirs()) {
            disk.add(p.getFileName().toString());
        }
        assertEquals(new TreeSet<String>(pomMods), disk,
                "[模块清单] 根 pom <modules> 与磁盘目录不一致（pom 独有=" + diff(new TreeSet<String>(pomMods), disk)
                        + "，磁盘独有=" + diff(disk, new TreeSet<String>(pomMods)) + "）");

        // README 结构树里点名的 z-agent-kernel-* 必须都是真模块
        Set<String> mentioned = new TreeSet<String>();
        Matcher m = Pattern.compile("z-agent-kernel-([a-z][a-z0-9]*)").matcher(readme());
        while (m.find()) {
            mentioned.add("z-agent-kernel-" + m.group(1));
        }
        for (String name : mentioned) {
            assertTrue(disk.contains(name), "[模块清单] README 点名的 " + name + " 不是本仓模块");
        }
        // 26 个模块都必须在 README 里被点名（不许漏）
        for (String name : disk) {
            assertTrue(mentioned.contains(name), "[模块清单] 模块 " + name + " 在 README 里一次都没出现");
        }

        // 项目结构节自称的 modules 条数
        String struct = section("项目结构");
        int claimed = intClaim(struct, Pattern.compile("共\\s*\\*\\*(\\d+)\\*\\*\\s*条"), "[项目结构] <modules> 条数");
        assertEquals(pomMods.size(), claimed, "[项目结构] README 写 " + claimed + " 条，根 pom 是 " + pomMods.size());
    }

    /** 根 DM：reactor 自钉条数、kotlin 四件套、z-util 三条，全与根 pom 现读一致。 */
    @Test
    public void 根依赖管理表与README一致() {
        List<String[]> dm = rootManagedDeps();
        int selfPinned = 0;
        List<String> kotlin = new ArrayList<String>();
        List<String> zutil = new ArrayList<String>();
        Set<String> selfNames = new TreeSet<String>();
        for (String[] d : dm) {
            if (d[2].contains("project.version")) {
                selfPinned++;
                selfNames.add(d[1]);
            }
            if (d[0].contains("kotlin")) {
                kotlin.add(d[1] + ":" + d[2]);
            }
            if (d[1].startsWith("z-util")) {
                zutil.add(d[1] + ":" + d[2]);
            }
        }
        assertEquals(26 + 1, selfPinned, "[根 DM] reactor 自钉条数与「根 + 26 模块」不符");

        // README 里两处都写了「27 条 ${project.version} 自钉」
        String struct = section("项目结构");
        int c1 = intClaim(struct, Pattern.compile("(\\d+) 条 reactor 自钉 DM"), "[根 DM] 结构树里的自钉条数");
        assertEquals(selfPinned, c1, "[根 DM] README 写 " + c1 + " 条自钉，根 pom 实数 " + selfPinned);
        String quick = section("快速开始");
        int c2 = intClaim(quick, Pattern.compile("根 DM 的 (\\d+) 条 `\\$\\{project\\.version\\}` 自钉"), "[根 DM] 快速开始里的自钉条数");
        assertEquals(selfPinned, c2, "[根 DM] README 写 " + c2 + " 条自钉，根 pom 实数 " + selfPinned);

        // kotlin 四件套 1.9.21
        assertEquals(4, kotlin.size(), "[根 DM] kotlin 条数不是 4：" + kotlin);
        for (String k : kotlin) {
            assertTrue(k.endsWith(":1.9.21"), "[根 DM] kotlin 版本不是 1.9.21：" + k);
        }
        Set<String> kotlinNames = new TreeSet<String>();
        for (String k : kotlin) {
            kotlinNames.add(k.substring(0, k.indexOf(':')));
        }
        assertEquals(new TreeSet<String>(Arrays.asList(
                "kotlin-stdlib", "kotlin-stdlib-common", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8")), kotlinNames,
                "[根 DM] kotlin 四件套名单不对：" + kotlinNames);

        // z-util 三条 1.2.4
        assertEquals(3, zutil.size(), "[根 DM] z-util 条数不是 3：" + zutil);
        for (String z : zutil) {
            assertTrue(z.endsWith(":1.2.4"), "[根 DM] z-util 版本不是 1.2.4：" + z);
        }
    }

    /** 主产物规模：文件数、总行数、类型分布、可发布坐标数，全部现读核对。 */
    @Test
    public void 主产物规模与类型分布一致() {
        String struct = section("项目结构");
        int claimedFiles = intClaim(struct, Pattern.compile("main `(\\d+)` 个 `\\.java`"), "[规模] main 文件数");
        List<Path> mains = mainJavaFiles();
        assertEquals(claimedFiles, mains.size(), "[规模] README 写 main " + claimedFiles + " 个 .java，实测 " + mains.size());

        int claimedLines = intClaim(struct, Pattern.compile("\\*\\*(\\d+)\\*\\* 行；test"), "[规模] main 总行数");
        int realLines = 0;
        for (Path p : mains) {
            realLines += wcLines(p);
        }
        assertEquals(claimedLines, realLines, "[规模] README 写 main " + claimedLines + " 行（wc -l 口径），实测 " + realLines);

        Map<String, Integer> tb = typeBreakdown();
        assertEquals(claimedFiles, tb.get("package-info") + tb.get("interface") + tb.get("class") + tb.get("enum"),
                "[规模] 类型分布四项之和 " + tb + " 与 main 文件数 " + claimedFiles + " 对不上");
        int ci = intClaim(struct, Pattern.compile("= (\\d+) `package-info`"), "[规模] package-info 数");
        int it = intClaim(struct, Pattern.compile("\\+ (\\d+) `interface`"), "[规模] interface 数");
        int cl = intClaim(struct, Pattern.compile("\\+ (\\d+) `class`"), "[规模] class 数");
        int en = intClaim(struct, Pattern.compile("\\+ (\\d+) `enum`"), "[规模] enum 数");
        assertEquals(ci, (int) tb.get("package-info"), "[规模] package-info 数不一致");
        assertEquals(it, (int) tb.get("interface"), "[规模] interface 数不一致");
        assertEquals(cl, (int) tb.get("class"), "[规模] class 数不一致");
        assertEquals(en, (int) tb.get("enum"), "[规模] enum 数不一致");

        // 「27 个坐标全部可发布」= 聚合根 + 26 模块
        int claimedCoords = intClaim(struct, Pattern.compile("所以 (\\d+) 个坐标全部可发布"), "[规模] 可发布坐标数");
        assertEquals(1 + pomModules().size(), claimedCoords, "[规模] 可发布坐标数不等于「根 + 模块数」");

        // 「没有任何模块设 maven.deploy.skip」
        int deploySkip = 0;
        for (String p : pomFilesMentioning("maven\\.deploy\\." + "skip")) {
            deploySkip++;
        }
        assertEquals(0, deploySkip, "[规模] README 写没有任何模块设 maven.deploy.skip，实测 " + deploySkip + " 个 pom 命中");
    }

    /** 「0 命中」类主张：Spring / 注解 / JDBC / 环境变量 / 资源目录 / 配置文件。 */
    @Test
    public void 零命中类主张全部成立() {
        // 摘要段：@RequestMapping / @RestController / @SpringBootApplication 实测 0 命中
        String head = readme().split("---", 2)[0];
        assertTrue(head.contains("**0 命中**"), "[摘要] 未声明 0 命中");
        int annotations = grepJavaCount("@Request" + "Mapping|@Rest" + "Controller|@Get" + "Mapping|@Post" + "Mapping|@Spring" + "BootApplication");
        assertEquals(0, annotations, "[摘要] README 写 Web 注解 0 命中，实测 " + annotations + " 处");
        int env = grepJavaCount("System\\.get" + "env");
        assertEquals(0, env, "[凭据] README 写 System.getenv 0 命中，实测 " + env + " 处");

        // 技术栈「未使用」行
        String tech = section("技术栈");
        int sql = grepJavaCount("java\\." + "sql|j" + "dbc|Data" + "Source|sq" + "lite");
        assertEquals(0, sql, "[技术栈] README 写 JDBC 一族 0 文件，实测 " + sql + " 处");
        assertTrue(tech.contains("命中 **0 文件**"), "[技术栈] 未使用行没写 0 文件：" + tech);

        // 库仓：无 resources / 无 application*.yml / 无端口
        int res = 0;
        for (Path p : mainJavaFiles()) {
            Path main = p;
            while (main != null && !"main".equals(main.getFileName().toString())) {
                main = main.getParent();
            }
            Path resDir = main == null ? null : main.resolve("resources");
            if (resDir != null && Files.isDirectory(resDir)) {
                res += 1;
            }
        }
        assertEquals(0, res, "[摘要] README 写全仓无 src/main/resources，实测 " + res + " 个模块有");

        int yml = 0;
        try (Stream<Path> s = Files.walk(repoRoot())) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (n.startsWith("application") && (n.endsWith(".yml") || n.endsWith(".yaml"))) {
                    yml++;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        assertEquals(0, yml, "[摘要] README 写无 application*.yml，实测 " + yml + " 个");

        // 「本仓不是应用」：0 个 Controller
        assertEquals(0, grepJavaCount("@Contro" + "ller|@Rest" + "Controller"), "[接口面] README 写 0 个 Controller，实测不为 0");
    }

    /** LLM provider：7 个类 + 7 个默认 base URL，逐个对。 */
    @Test
    public void provider名单与baseUrl逐个对() {
        Path provDir = repoRoot().resolve("z-agent-kernel-llm/src/main/java/com/zifang/z/agent/kernel/llm/provider");
        assertTrue(Files.isDirectory(provDir), "[provider] provider 目录不存在");
        Set<String> classes = new TreeSet<String>();
        for (Path p : listFiles(provDir)) {
            if (p.getFileName().toString().endsWith("Provider.java")) {
                classes.add(p.getFileName().toString().replace(".java", ""));
            }
        }
        assertEquals(7, classes.size(), "[provider] provider 类数不是 7：" + classes);
        assertEquals(new TreeSet<String>(Arrays.asList(
                "AnthropicProvider", "DashScopeProvider", "DeepSeekProvider", "GeminiProvider",
                "OllamaProvider", "OpenAIProvider", "QwenProvider")), classes,
                "[provider] provider 类名单不对：" + classes);

        // 每个 provider 的 DEFAULT_BASE_URL
        Map<String, String> urls = new TreeMap<String, String>();
        for (Path p : listFiles(provDir)) {
            if (!p.getFileName().toString().endsWith("Provider.java")) {
                continue;
            }
            Matcher m = Pattern.compile("DEFAULT_BASE_URL\\s*=\\s*\"([^\"]+)\"").matcher(readText(p));
            assertTrue(m.find(), "[provider] " + p.getFileName() + " 没有 DEFAULT_BASE_URL");
            urls.put(p.getFileName().toString().replace(".java", ""), m.group(1));
        }
        assertEquals(7, urls.size(), "[provider] DEFAULT_BASE_URL 个数不是 7：" + urls.keySet());

        // README 能力清单行里列的 base URL 必须与源码逐字一致
        String cap = section("能力清单（每条都对应到类）");
        String capLine = null;
        for (String line : cap.split("\n")) {
            if (line.contains("OpenAI") && line.contains("Anthropic")) {
                capLine = line;
                break;
            }
        }
        assertNotNull(capLine, "[能力清单] 找不到多 provider 那一行");
        for (Map.Entry<String, String> e : urls.entrySet()) {
            String host = e.getValue().replaceFirst("^https?://", "");
            assertTrue(capLine.contains(host),
                    "[能力清单] README 没写 " + e.getKey() + " 的 base URL 片段 " + host + "（源码是 " + e.getValue() + "）");
        }
    }

    /** 关键类型与常量：MessageRole / DEFAULT_MAX_TOKENS / 线程池 / 协议版本 / Direction / 异常类。 */
    @Test
    public void 关键类型与常量逐个对() {
        // MessageRole 5 值
        Path role = findSource("types", "MessageRole");
        assertNotNull(role, "[types] 找不到 MessageRole");
        String rt = readText(role);
        Matcher rm = Pattern.compile("^\\s{4}([A-Z_]+),?\\s*$", Pattern.MULTILINE).matcher(rt);
        Set<String> roles = new TreeSet<String>();
        while (rm.find()) {
            roles.add(rm.group(1));
        }
        assertEquals(new TreeSet<String>(Arrays.asList("ASSISTANT", "FUNCTION", "SYSTEM", "TOOL", "USER")), roles,
                "[types] MessageRole 取值集合不对：" + roles);
        assertTrue(readme().contains("MessageRole（5 值）"), "[项目结构] README 没写 MessageRole 5 值");

        // DEFAULT_MAX_TOKENS
        Path app = repoRoot().resolve("z-agent-kernel-app/src/main/java/com/zifang/z/agent/kernel/app/agent/LlmAgent.java");
        assertTrue(Files.isRegularFile(app), "[app] 找不到 LlmAgent.java");
        String at = readText(app);
        Matcher mm = Pattern.compile("DEFAULT_MAX_TOKENS\\s*=\\s*(\\d+)L?").matcher(at);
        assertTrue(mm.find(), "[app] LlmAgent 没有 DEFAULT_MAX_TOKENS");
        assertEquals("128000", mm.group(1), "[能力清单] README 写 DEFAULT_MAX_TOKENS=128000，源码是 " + mm.group(1));
        assertTrue(readme().contains("`DEFAULT_MAX_TOKENS=128000`"), "[能力清单] README 没写 DEFAULT_MAX_TOKENS=128000");

        // AsyncEventBus 固定 4 线程
        Path bus = findSource("event", "AsyncEventBus");
        assertNotNull(bus, "[event] 找不到 AsyncEventBus");
        Matcher pm = Pattern.compile("newFixedThreadPool\\((\\d+)").matcher(readText(bus));
        assertTrue(pm.find(), "[event] AsyncEventBus 没有 newFixedThreadPool");
        assertEquals(4, Integer.parseInt(pm.group(1)), "[能力清单] README 写异步版默认 4 线程池，源码是 " + pm.group(1));
        assertTrue(readme().contains("默认 4 线程池"), "[能力清单] README 没写默认 4 线程池");

        // MCP 协议版本
        Path mcp = findSource("mcp", "StdioMcpTransport");
        assertNotNull(mcp, "[mcp] 找不到 StdioMcpTransport");
        assertTrue(readText(mcp).contains("2024-11-05"), "[能力清单] StdioMcpTransport 里没有 protocolVersion 2024-11-05");
        assertTrue(readme().contains("protocolVersion=2024-11-05"), "[能力清单] README 没写 protocolVersion=2024-11-05");

        // Formatter.Direction 三值
        Path fmt = findSource("formatter", "Formatter");
        assertNotNull(fmt, "[formatter] 找不到 Formatter");
        Matcher dm = Pattern.compile("enum\\s+Direction\\s*\\{([^}]*)\\}", Pattern.DOTALL).matcher(readText(fmt));
        assertTrue(dm.find(), "[formatter] 找不到 enum Direction");
        Set<String> dirs = new TreeSet<String>();
        for (String s : dm.group(1).split(",")) {
            s = s.trim();
            if (!s.isEmpty()) {
                dirs.add(s);
            }
        }
        assertEquals(new TreeSet<String>(Arrays.asList("BIDIRECTIONAL", "REQUEST", "RESPONSE")), dirs,
                "[接口面] Direction 取值集合不对：" + dirs);
        assertTrue(readme().contains("Direction ∈ {REQUEST, RESPONSE, BIDIRECTIONAL}"), "[接口面] README 没写 Direction 三值");

        // 5 个内嵌异常类
        for (String[] pair : new String[][] {
                {"llm", "LlmException"},
                {"credential", "CredentialStore"},
                {"permission", "PermissionCheck"},
                {"middleware", "MiddlewareChain"},
                {"agent", "InterruptFlag"}}) {
            Path p = findSource(pair[0], pair[1]);
            assertNotNull(p, "[缺陷] 找不到 " + pair[1]);
            assertTrue(readText(p).contains("Exception"), "[缺陷] " + pair[1] + " 里没有 Exception 类");
        }
        assertTrue(readme().contains("`LlmException`"), "[缺陷] README 没列 LlmException");
    }

    /** 跨模块 import 边：README 那张表逐格对，且「零跨模块引用」的那组模块确实没有边。 */
    @Test
    public void 跨模块import边表逐格对() {
        Map<String, Map<String, Integer>> edges = importEdges();
        String struct = section("项目结构");
        List<String> lines = new ArrayList<String>();
        for (String line : struct.split("\n")) {
            if (line.matches("^[a-z]+ +-> .*")) {
                lines.add(line.trim());
            }
        }
        assertFalse(lines.isEmpty(), "[import 边] README 里找不到 import 边表");
        int total = 0;
        for (String line : lines) {
            Matcher m = Pattern.compile("^(\\w+)\\s+->\\s+(.*)$").matcher(line);
            assertTrue(m.find(), "[import 边] 解析不了这行：" + line);
            String from = m.group(1);
            Map<String, Integer> claimed = new TreeMap<String, Integer>();
            Matcher pm = Pattern.compile("(\\w+)\\((\\d+)\\)").matcher(m.group(2));
            while (pm.find()) {
                claimed.put(pm.group(1), Integer.parseInt(pm.group(2)));
                total += Integer.parseInt(pm.group(2));
            }
            Map<String, Integer> real = edges.containsKey(from) ? edges.get(from) : new TreeMap<String, Integer>();
            assertEquals(real, claimed, "[import 边] 模块 " + from + " 的跨模块引用边对不上：README " + claimed + "，源码 " + real);
        }
        assertTrue(total > 0, "[import 边] 一条边都没抠出来");

        // 「pipeline / rag / state / workspace / formatter / mcp / credential 之间零跨模块引用」
        for (String mod : new String[] {"pipeline", "rag", "state", "workspace", "formatter", "mcp", "credential"}) {
            assertFalse(edges.containsKey(mod), "[import 边] README 写 " + mod + " 零跨模块引用，实测有：" + edges.get(mod));
        }
    }

    /** 对外接口面表：SPI 名 + 所在模块 + 方法名，逐个到源码里找。 */
    @Test
    public void 对外接口面表逐行核对() {
        String sec = section("对外接口面");
        int rows = 0;
        for (String line : sec.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("|") || t.contains("---") || t.startsWith("| SPI")) {
                continue;
            }
            String[] cells = splitRow(t);
            if (cells.length < 3) {
                continue;
            }
            List<String> spis = splitSlash(cells[0]);
            List<String> mods = splitSlash(cells[1]);
            List<String> segs = splitSemicolon(cells[2]);
            for (int i = 0; i < spis.size(); i++) {
                String spi = spis.get(i);
                String module = mods.size() == spis.size() ? mods.get(i) : mods.get(0);
                String cell = segs.size() == spis.size() ? segs.get(i) : cells[2];
                rows++;
                Path src = findSource(module, spi);
                assertNotNull(src, "[接口面] 模块 " + module + " 里找不到 " + spi + ".java");
                Set<String> declared = declaredMethodNames(src);
                List<String> mentioned = new ArrayList<String>();
                for (String span : codeSpans(cell)) {
                    String head = span;
                    int dot = head.lastIndexOf('.');
                    if (dot >= 0) {
                        head = head.substring(dot + 1);
                    }
                    int par = head.indexOf('(');
                    if (par >= 0) {
                        head = head.substring(0, par);
                    }
                    head = head.trim();
                    if (!head.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                        continue;
                    }
                    // 大写开头是类型/常量（ToolResult / StepResult / PermissionDeniedException …），
                    // 不是方法，不参与「方法必须在源码里存在」的判定
                    if (!Character.isLowerCase(head.charAt(0))) {
                        continue;
                    }
                    mentioned.add(head);
                }
                assertFalse(mentioned.isEmpty(), "[接口面] " + spi + " 那一行抠不出任何方法名");
                for (String mth : mentioned) {
                    assertTrue(declared.contains(mth),
                            "[接口面] " + spi + "（" + module + "）README 写了方法 " + mth + "，源码里没有");
                }
            }
        }
        assertTrue(rows >= 20, "[接口面] 只核到 " + rows + " 个 SPI，表可能没解析对");
    }

    /** 按 "/" 拆分表格格（去掉反引号与空白）。 */
    private static List<String> splitSlash(String cell) {
        List<String> out = new ArrayList<String>();
        for (String s : cell.split("/")) {
            s = s.replace("`", "").trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 按全角分号拆分表格格（一个 SPI 一段）。 */
    private static List<String> splitSemicolon(String cell) {
        List<String> out = new ArrayList<String>();
        for (String s : cell.split("\uff1b")) {
            if (!s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    /** 已知缺陷：写死版本号与响应配对写法，必须与源码逐字对得上。 */
    @Test
    public void 已知缺陷字面量与源码一致() {
        String sec = section("已知未收口的缺陷（读过代码确认在，不是测出来的红）");
        Path mcp = findSource("mcp", "StdioMcpTransport");
        assertNotNull(mcp, "[缺陷] 找不到 StdioMcpTransport");
        String src = readText(mcp);

        // README 贴的响应配对片段必须逐字出现在源码里
        String snippet = "trimmed.contains(\"\\\"id\\\":\" + id)";
        assertTrue(src.contains(snippet),
                "[缺陷] 源码里没有 README 贴的配对写法：" + snippet);
        assertTrue(sec.contains("`" + snippet + "`"),
                "[缺陷] README 没贴源码真身的配对写法：" + snippet);

        // clientInfo 版本写死 0.2.0
        int ci = src.indexOf("clientInfo");
        assertTrue(ci >= 0, "[缺陷] 源码里没有 clientInfo");
        String around = src.substring(ci, Math.min(src.length(), ci + 120));
        assertTrue(around.contains("0.2.0"), "[缺陷] clientInfo 附近的版本不是写死的 0.2.0：" + around);
        assertTrue(sec.contains("`\"0.2.0\"`"), "[缺陷] README 没写 clientInfo.version 写死 0.2.0");
    }

    /** 发布配置：central profile 的关键项与根 pom 现读一致。 */
    @Test
    public void 发布配置与根pom一致() {
        String pub = section("发布");
        assertEquals("0.8.0", rootProperty("central-publishing-plugin.version"), "[发布] 根 pom 的 central-publishing 插件版本变了");
        assertTrue(pub.contains("central-publishing-maven-plugin 0.8.0"), "[发布] README 没写 central-publishing-maven-plugin 0.8.0");

        String pom = rootPom();
        assertTrue(pom.contains("<autoPublish>true</autoPublish>"), "[发布] central profile 没有 autoPublish=true");
        assertTrue(pom.contains("<waitUntil>uploaded</waitUntil>"), "[发布] central profile 没有 waitUntil=uploaded");
        assertTrue(pom.contains("<skip>true</skip>"), "[发布] maven-deploy-plugin 没有显式 skip=true");
        assertTrue(pub.contains("`autoPublish=true`"), "[发布] README 没写 autoPublish=true");
        assertTrue(pub.contains("`waitUntil=uploaded`"), "[发布] README 没写 waitUntil=uploaded");
        assertTrue(pub.contains("skip=true"), "[发布] README 没写 maven-deploy-plugin skip=true");

        // flatten：版本由父链下发（本仓不可验），只钉仓内可验的结构性项
        assertTrue(pom.contains("<flattenMode>oss</flattenMode>"), "[发布] flattenMode 不是 oss");
        assertTrue(pom.contains("<updatePomFile>true</updatePomFile>"), "[发布] updatePomFile 不是 true");
        String tech = section("技术栈");
        assertTrue(tech.contains("flatten-maven-plugin:1.7.3"), "[技术栈] README 没写 flatten-maven-plugin:1.7.3");
        assertTrue(tech.contains("`oss`"), "[技术栈] README 没写 flattenMode=oss");
    }

    /** 依赖事实：llm 的依赖名单、okhttp 只在 llm、全仓零 Jackson 坐标。 */
    @Test
    public void 依赖名单与零Jackson成立() {
        String llmPom = stripXmlComments(readText(repoRoot().resolve("z-agent-kernel-llm/pom.xml")));
        List<String> deps = new ArrayList<String>();
        Matcher dep = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(llmPom);
        while (dep.find()) {
            String body = dep.group(1);
            deps.add(tag(body, "groupId") + ":" + tag(body, "artifactId"));
        }
        assertEquals(6, deps.size(), "[技术栈] llm 模块依赖数不是 6：" + deps);
        assertTrue(deps.contains("com.squareup.okhttp3:okhttp"), "[技术栈] llm 没依赖 okhttp：" + deps);
        assertTrue(deps.contains("com.squareup.okhttp3:okhttp-sse"), "[技术栈] llm 没依赖 okhttp-sse：" + deps);
        assertTrue(deps.contains("io.github.yuku123:z-util-parser-json"), "[技术栈] llm 没依赖 z-util-parser-json：" + deps);

        // okhttp / okhttp-sse 只在 llm
        for (Path m : moduleDirs()) {
            if (m.getFileName().toString().equals("z-agent-kernel-llm")) {
                continue;
            }
            String mp = stripXmlComments(readText(m.resolve("pom.xml")));
            assertFalse(mp.contains("<artifactId>okhttp</artifactId>"),
                    "[技术栈] okhttp 不止 llm 在用：" + m.getFileName());
            assertFalse(mp.contains("<artifactId>okhttp-sse</artifactId>"),
                    "[技术栈] okhttp-sse 不止 llm 在用：" + m.getFileName());
        }

        // 全仓零 Jackson 坐标 + 源码零 fasterxml import
        List<String> jacksonPoms = pomFilesMentioning("<artifactId>jackson" + "-");
        assertTrue(jacksonPoms.isEmpty(), "[技术栈] README 写全仓零 Jackson 坐标，实测这些 pom 声明了：" + jacksonPoms);
        int fj = grepJavaCount("com\\.faster" + "xml");
        assertEquals(0, fj, "[技术栈] README 写源码零 fasterxml import，实测 " + fj + " 处");

        String tech = section("技术栈");
        assertTrue(tech.contains("全仓零 Jackson"), "[技术栈] README 的 JSON 行没写「全仓零 Jackson」");
        assertTrue(tech.contains("z-util-parser-json"), "[技术栈] README 的 JSON 行没写 z-util-parser-json");
    }

    /** 测试分布：@Test 支数按「排除本守卫文件」口径，与 README 两处说法一致。 */
    @Test
    public void 测试分布与README一致() {
        Map<String, Integer> counts = testCountPerModule();
        Map<String, Integer> nonZero = new TreeMap<String, Integer>();
        List<String> zero = new ArrayList<String>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 0) {
                nonZero.put(e.getKey(), e.getValue());
            } else {
                zero.add(e.getKey());
            }
        }

        // 「哪些面是有名字没东西的」第 2 条
        String faces = section("哪些\"面\"是有名字没东西的");
        int totalClaim = intClaim(faces, Pattern.compile("\\*\\*(\\d+) 支 `@Test` 只落在 (\\d+) 个模块\\*\\*"), "[测试] 支数");
        int modClaim = intClaim(faces, Pattern.compile("\\*\\*\\d+ 支 `@Test` 只落在 (\\d+) 个模块\\*\\*"), "[测试] 模块数");
        assertEquals(12, modClaim, "[测试] README 写 @Test 落在 " + modClaim + " 个模块");
        assertEquals(nonZero.size(), modClaim, "[测试] 实测有测试的模块数是 " + nonZero.size() + "，README 写 " + modClaim);
        assertEquals(totalClaim, sum(nonZero), "[测试] README 写 " + totalClaim + " 支，实测 " + sum(nonZero));

        int zeroClaim = intClaim(faces, Pattern.compile("其余 \\*\\*(\\d+) 个模块零测试\\*\\*"), "[测试] 零测试模块数");
        assertEquals(zero.size(), zeroClaim, "[测试] README 写 " + zeroClaim + " 个模块零测试，实测 " + zero.size());
        // README 列了零测试模块名单，名单必须与实算一致
        String zeroNamed = faces.substring(faces.indexOf("其余 **" + zeroClaim + " 个模块零测试**"));
        int lp = zeroNamed.indexOf('\uff08');
        int rp = zeroNamed.indexOf('\uff09');
        assertTrue(lp >= 0 && rp > lp, "[测试] 零测试那一行没有列出模块名单");
        Set<String> readmeZero = new TreeSet<String>();
        Matcher zm = Pattern.compile("`(\\w+)`").matcher(zeroNamed.substring(lp + 1, rp));
        while (zm.find()) {
            readmeZero.add(zm.group(1));
        }
        assertEquals(new TreeSet<String>(zero), readmeZero,
                "[测试] 零测试模块名单对不上：README " + readmeZero + "，实算 " + new TreeSet<String>(zero));

        // 「🧪 测试」段的分布行，逐格对
        String testSec = section("测试");
        String distLine = null;
        for (String line : testSec.split("\n")) {
            if (line.contains("分布") && line.contains("`tool`")) {
                distLine = line;
                break;
            }
        }
        assertNotNull(distLine, "[测试] 找不到分布那一行");
        Matcher dm = Pattern.compile("`(\\w+)` (\\d+)").matcher(distLine);
        Map<String, Integer> readmeDist = new TreeMap<String, Integer>();
        while (dm.find()) {
            readmeDist.put(dm.group(1), Integer.parseInt(dm.group(2)));
        }
        assertEquals(nonZero, readmeDist, "[测试] 分布行与实算不一致：README " + readmeDist + "，实算 " + nonZero);

        // mcp 那 9 支：3 + 6
        Path mcpTest = repoRoot().resolve(
                "z-agent-kernel-mcp/src/test/java/com/zifang/z/agent/kernel/mcp/StdioMcpTransportTest.java");
        Path mcpTimeout = repoRoot().resolve(
                "z-agent-kernel-mcp/src/test/java/com/zifang/z/agent/kernel/mcp/StdioMcpTransportTimeoutTest.java");
        assertTrue(Files.isRegularFile(mcpTest) && Files.isRegularFile(mcpTimeout), "[测试] mcp 两个测试文件不在盘");
        assertEquals(3, countTestAnnotations(readText(mcpTest)), "[测试] StdioMcpTransportTest 不是 3 支");
        assertEquals(6, countTestAnnotations(readText(mcpTimeout)), "[测试] StdioMcpTransportTimeoutTest 不是 6 支");
        assertEquals(9, (int) nonZero.get("mcp"), "[测试] mcp 模块不是 9 支");
        assertTrue(testSec.contains("那 9 支"), "[测试] README 没写 mcp 那 9 支");
    }

    /** 文档目录与 License：点名的路径在盘，根 pom 的 licenses 与 README 一致。 */
    @Test
    public void 文档目录与License在盘() {
        String docSec = section("文档目录");
        List<String> paths = new ArrayList<String>();
        Matcher m = Pattern.compile("\\]\\((_doc/[^)]+)\\)").matcher(docSec);
        while (m.find()) {
            paths.add(m.group(1));
        }
        assertFalse(paths.isEmpty(), "[文档目录] README 没点名任何 _doc 路径");
        for (String p : paths) {
            assertTrue(Files.exists(repoRoot().resolve(p)), "[文档目录] README 点名的 " + p + " 在盘上不存在");
        }
        assertTrue(Files.isRegularFile(repoRoot().resolve("_doc/001_arch/00-overview.md")),
                "[文档目录] _doc/001_arch/00-overview.md 不在盘");

        assertTrue(Files.isRegularFile(repoRoot().resolve("LICENSE")), "[License] LICENSE 文件不在盘");
        String pom = rootPom();
        assertTrue(pom.contains("<name>MIT License</name>"), "[License] 根 pom 的 licenses 不是 MIT");
        String lic = section("License");
        assertTrue(lic.contains("MIT"), "[License] README 没写 MIT");
    }

    // ---------------------------------------------------------------- 工具

    private static int sum(Map<String, Integer> m) {
        int n = 0;
        for (Integer v : m.values()) {
            n += v;
        }
        return n;
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<String>(a);
        out.removeAll(b);
        return out;
    }
}
