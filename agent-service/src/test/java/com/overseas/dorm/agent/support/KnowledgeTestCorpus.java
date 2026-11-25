package com.overseas.dorm.agent.support;

import com.overseas.dorm.agent.rag.MarkdownChunker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 真实知识库的离线语料夹具：把 {@code agent-service/src/main/resources/knowledge/*.md} 读进来。
 *
 * 生产的知识库加载是 {@code KnowledgeBaseLoader}：它要 Spring 上下文、MySQL、Redis
 * 和嵌入缓存，启动一次几十秒。但"切分得对不对、检索得准不准"这些问题
 * <b>根本不需要数据库</b>，它们只依赖文本、分块参数和嵌入模型。
 *
 * <p>于是本夹具把那条链里<b>与存储无关</b>的部分单独拎出来：
 * 读文件 → 解析 front-matter → 交给我方的 {@link MarkdownChunker}。
 * 用的是<b>真实文档、真实参数、真实分块器</b>，只是不落库。
 * 代价是它不覆盖"版本门控重建索引"这类存储侧行为，那些留给需要数据库的加餐。
 *
 * 第 06 章用它验证切分不变量，第 07 章用它做检索评测。
 * 共享夹具放在这里，而不是各写一份，两份会漂移，而漂移的语料
 * 会让两章的数字互相矛盾。
 */
public final class KnowledgeTestCorpus {

    /** 生产默认分块参数（与 {@code dorm.agent.rag.chunk-size} / {@code chunk-overlap} 一致）。 */
    public static final int DEFAULT_CHUNK_SIZE = 400;
    public static final int DEFAULT_CHUNK_OVERLAP = 60;

    private KnowledgeTestCorpus() {
    }

    /**
     * 一篇知识文档。
     *
     * @param docCode    文档业务编码（front-matter 里的 docCode），检索评测的"标准答案"就是它
     * @param title      文档标题
     * @param category   分类
     * @param regionCode 区域编码；{@code GLOBAL} 表示所有区域可见
     * @param path       源文件路径（排查用）
     * @param body       去掉 front-matter 之后的正文
     */
    public record Doc(String docCode, String title, String category, String regionCode,
                      Path path, String body) {
    }

    /**
     * 一个切片，外加它来自哪篇文档。
     *
     * <p>生产里这个归属关系存在 {@code rag_chunk.doc_code} 列上；
     * 离线夹具不需要数据库，就在内存里带着走。
     */
    public record ChunkRef(String docCode, String title, String content, int index) {
    }

    /**
     * 定位知识库目录。
     *
     * <p>从当前工作目录向上找 {@code agent-service/src/main/resources/knowledge}。
     * 不写死相对路径（{@code ../dorm-boot/...}）是因为测试的工作目录
     * 取决于从哪里调用 Maven，写死会在某些调用方式下悄悄找不到文件，
     * 而"悄悄找不到"在这里等于"测试什么也没验证"。
     */
    public static Path knowledgeDir() {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            Path dir = candidate.resolve("agent-service/src/main/resources/knowledge");
            if (Files.isDirectory(dir)) {
                return dir;
            }
        }
        throw new IllegalStateException(
                "找不到知识库目录：从 " + start + " 向上未发现 agent-service/src/main/resources/knowledge。"
                        + "本套测试依赖仓库的目录结构，请不要在别处单独运行它们。");
    }

    /** 读入全部知识文档，按 docCode 排序（顺序稳定，便于对比两次运行的数字）。 */
    public static List<Doc> docs() {
        Path dir = knowledgeDir();
        try (Stream<Path> files = Files.list(dir)) {
            List<Doc> docs = new ArrayList<>();
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".md")).toList()) {
                docs.add(parse(file));
            }
            docs.sort(Comparator.comparing(Doc::docCode));
            if (docs.isEmpty()) {
                throw new IllegalStateException("知识库目录里没有 .md 文件：" + dir);
            }
            return List.copyOf(docs);
        } catch (IOException e) {
            throw new UncheckedIOException("读取知识库失败：" + dir, e);
        }
    }

    /** 按生产参数把全部文档切成块，并保留"这块属于哪篇文档"。 */
    public static List<ChunkRef> chunks() {
        return chunks(DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }

    /** 按指定参数切块。 */
    public static List<ChunkRef> chunks(int chunkSize, int overlap) {
        List<ChunkRef> chunks = new ArrayList<>();
        for (Doc doc : docs()) {
            for (MarkdownChunker.Chunk chunk : MarkdownChunker.chunk(doc.body(), chunkSize, overlap)) {
                chunks.add(new ChunkRef(doc.docCode(), chunk.title(), chunk.content(), chunk.index()));
            }
        }
        return List.copyOf(chunks);
    }

    private static Doc parse(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, String> front = frontMatter(raw);
        return new Doc(
                front.getOrDefault("docCode", fileNameCode(file)),
                front.getOrDefault("title", ""),
                front.getOrDefault("category", ""),
                front.getOrDefault("regionCode", ""),
                file,
                stripFrontMatter(raw));
    }

    private static String fileNameCode(Path file) {
        return file.getFileName().toString().replace(".md", "").toUpperCase().replace('-', '-');
    }

    /**
     * 解析 YAML front-matter（只支持本仓库用到的 {@code key: value} 单行形式）。
     *
     * <p>刻意不引入 YAML 库：这里只需要读几个标量字段，
     * 而多一个解析依赖就多一份"它和生产的解析器行为不一致"的风险。
     */
    public static Map<String, String> frontMatter(String markdown) {
        Map<String, String> map = new LinkedHashMap<>();
        if (markdown == null || !markdown.startsWith("---")) {
            return map;
        }
        int end = markdown.indexOf("\n---", 3);
        if (end < 0) {
            return map;
        }
        for (String line : markdown.substring(3, end).split("\n")) {
            String trimmed = line.strip();
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            map.put(trimmed.substring(0, colon).strip(), trimmed.substring(colon + 1).strip());
        }
        return map;
    }

    /** 去掉 front-matter，只留正文。 */
    public static String stripFrontMatter(String markdown) {
        if (markdown == null || !markdown.startsWith("---")) {
            return markdown == null ? "" : markdown;
        }
        int end = markdown.indexOf("\n---", 3);
        if (end < 0) {
            return markdown;
        }
        int bodyStart = markdown.indexOf('\n', end + 1);
        return bodyStart < 0 ? "" : markdown.substring(bodyStart + 1);
    }

    /** 把连续空白折成单个空格，便于做"这段文字在不在切片里"的包含比较。 */
    public static String collapse(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }
}
