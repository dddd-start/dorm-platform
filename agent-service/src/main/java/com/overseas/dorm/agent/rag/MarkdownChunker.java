package com.overseas.dorm.agent.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 分块器。
 *
 * 硬切最省事，但会把一个完整的规则条款切成两半：
 * <pre>
 *   切片 A：……员工需在入住日次日 23:59 前
 *   切片 B：办理入住，否则申请自动失效……
 * </pre>
 * 检索时如果只召回切片 A，模型看到的是"一句没说完的话"，
 * 要么答不出来，要么编造后半句，<b>这是 RAG 幻觉最常见的来源</b>。
 *
 * <p>制度文档天然具有层级结构（章 → 节 → 条款），
 * 按标题切分能让每个切片都是语义完整的单元。
 * 只有当单个小节本身超长时，才退化为按段落切分。
 *
 * 段落边界切分仍可能切断一个跨段落的因果表述。
 * 保留 {@code overlap} 个字符的重叠，可以让关键句子至少在一个切片里是完整的。
 * 代价是少量冗余的 token，这个交换是值得的。
 */
public final class MarkdownChunker {

    /** 一个切片。 */
    public record Chunk(int index, String title, String content) {
    }

    private MarkdownChunker() {
    }

    /**
     * 执行分块。
     *
     * @param markdown  文档正文
     * @param chunkSize 目标切片长度（字符）
     * @param overlap   重叠长度（字符）
     */
    public static List<Chunk> chunk(String markdown, int chunkSize, int overlap) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        List<Chunk> result = new ArrayList<>();
        List<Section> sections = splitByHeading(markdown);
        int index = 0;
        for (Section section : sections) {
            List<String> pieces = splitBySize(section.content(), chunkSize, overlap);
            for (String piece : pieces) {
                if (piece.isBlank()) {
                    continue;
                }
                result.add(new Chunk(index++, section.title(), piece));
            }
        }
        return result;
    }

    private record Section(String title, String content) {
    }

    /** 按 # / ## / ### 标题切分，标题路径用 " / " 连接，保留层级语义。 */
    private static List<Section> splitByHeading(String markdown) {
        List<Section> sections = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);
        StringBuilder buffer = new StringBuilder();
        List<String> titlePath = new ArrayList<>();
        String currentTitle = "正文";

        for (String line : lines) {
            String trimmed = line.strip();
            int level = headingLevel(trimmed);
            if (level > 0) {
                // 遇到新标题：先把上一节收尾
                if (buffer.toString().strip().length() > 0) {
                    sections.add(new Section(currentTitle, buffer.toString().strip()));
                    buffer.setLength(0);
                }
                String text = trimmed.substring(level).strip();
                // 维护标题路径：h1 覆盖，h2 追加，h3 追加
                while (titlePath.size() >= level) {
                    titlePath.remove(titlePath.size() - 1);
                }
                titlePath.add(text);
                currentTitle = String.join(" / ", titlePath);
            } else {
                buffer.append(line).append('\n');
            }
        }
        if (buffer.toString().strip().length() > 0) {
            sections.add(new Section(currentTitle, buffer.toString().strip()));
        }
        return sections;
    }

    private static int headingLevel(String line) {
        if (!line.startsWith("#")) {
            return 0;
        }
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        if (level > 6 || level >= line.length() || line.charAt(level) != ' ') {
            return 0;
        }
        return level;
    }

    /**
     * 超长小节按段落切分。
     *
     * <p>切分点优先选段落边界（空行），其次选句号，最后才硬切，* 这个优先级保证切出来的片段尽可能语义完整。
     */
    private static List<String> splitBySize(String text, int chunkSize, int overlap) {
        List<String> pieces = new ArrayList<>();
        if (text.length() <= chunkSize) {
            pieces.add(text);
            return pieces;
        }
        String[] paragraphs = text.split("\n\n+");
        StringBuilder buffer = new StringBuilder();
        for (String paragraph : paragraphs) {
            String p = paragraph.strip();
            if (p.isEmpty()) {
                continue;
            }
            // 单段本身就超长：先切句
            if (p.length() > chunkSize) {
                if (buffer.length() > 0) {
                    pieces.add(buffer.toString().strip());
                    buffer.setLength(0);
                }
                pieces.addAll(splitBySentence(p, chunkSize, overlap));
                continue;
            }
            if (buffer.length() + p.length() + 1 > chunkSize && buffer.length() > 0) {
                pieces.add(buffer.toString().strip());
                // 保留尾部重叠，避免跨段落的关键句被切断
                String tail = tail(buffer.toString(), overlap);
                buffer.setLength(0);
                buffer.append(tail);
            }
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(p);
        }
        if (buffer.toString().strip().length() > 0) {
            pieces.add(buffer.toString().strip());
        }
        return pieces;
    }

    private static List<String> splitBySentence(String text, int chunkSize, int overlap) {
        List<String> pieces = new ArrayList<>();
        String[] sentences = text.split("(?<=[。！？；!?;])");
        StringBuilder buffer = new StringBuilder();
        for (String sentence : sentences) {
            if (buffer.length() + sentence.length() > chunkSize && buffer.length() > 0) {
                pieces.add(buffer.toString().strip());
                String tail = tail(buffer.toString(), overlap);
                buffer.setLength(0);
                buffer.append(tail);
            }
            if (sentence.length() > chunkSize) {
                // 极端情况（无标点的超长文本）：硬切，保证不会无限增长
                if (buffer.length() > 0) {
                    pieces.add(buffer.toString().strip());
                    buffer.setLength(0);
                }
                for (int i = 0; i < sentence.length(); i += chunkSize) {
                    pieces.add(sentence.substring(i, Math.min(sentence.length(), i + chunkSize)));
                }
                continue;
            }
            buffer.append(sentence);
        }
        if (buffer.toString().strip().length() > 0) {
            pieces.add(buffer.toString().strip());
        }
        return pieces;
    }

    private static String tail(String text, int length) {
        if (length <= 0 || text.isEmpty()) {
            return "";
        }
        return text.length() <= length ? text : text.substring(text.length() - length);
    }
}
