package com.overseas.dorm.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Markdown 分块器测试。
 *
 * <p>分块质量直接决定 RAG 的上限：切片切碎了，再好的检索和模型也救不回来。
 * 本测试重点验证三条：
 * <ol>
 *   <li><b>不丢内容</b>：所有切片拼起来（去重后）应覆盖原文的全部实质性内容；</li>
 *   <li><b>不切碎语义单元</b>：标题层级被保留在切片的 title 里，便于引用与过滤；</li>
 *   <li><b>单块不超限</b>：超长小节会被继续切分，且切分点尽量落在段落/句子边界。</li>
 * </ol>
 */
class MarkdownChunkerTest {

    @Test
    @DisplayName("按标题切分，标题路径保留层级")
    void splitByHeading() {
        String markdown = """
                # 入住管理规定

                总则部分的内容，说明适用范围。

                ## 申请流程

                员工需要提前提交申请。审批时限为 24 小时。

                ### 特殊情况

                紧急出差可以先入住后补手续。

                ## 退房流程

                退房需在退房日 12:00 前完成。
                """;
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(markdown, 400, 60);

        assertThat(chunks).isNotEmpty();
        // 标题路径应形如 "入住管理规定 / 申请流程"
        assertThat(chunks.stream().map(MarkdownChunker.Chunk::title))
                .anyMatch(t -> t.contains("入住管理规定") && t.contains("申请流程"));
        assertThat(chunks.stream().map(MarkdownChunker.Chunk::title))
                .anyMatch(t -> t.contains("特殊情况"));
        // 内容不被截断
        assertThat(chunks.stream().map(MarkdownChunker.Chunk::content).reduce("", String::concat))
                .contains("审批时限为 24 小时")
                .contains("退房日 12:00 前");
    }

    @Test
    @DisplayName("超长小节会按段落继续切分，且每块不超过上限太多")
    void splitLongSection() {
        StringBuilder sb = new StringBuilder("# 长文档\n\n");
        for (int i = 0; i < 40; i++) {
            sb.append("这是第 ").append(i).append(" 段内容，").append("说明一些规定细节。".repeat(5)).append("\n\n");
        }
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(sb.toString(), 300, 50);

        assertThat(chunks.size()).isGreaterThan(1);
        // 允许因为不做硬切而略超上限，但不能失控
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThan(300 * 3));
        // 序号连续
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("无标点超长文本不会导致无限增长（硬切兜底）")
    void hardSplitFallback() {
        String text = "# 标题\n\n" + "无标点长文本".repeat(300);
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(text, 200, 40);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(200));
    }

    @Test
    @DisplayName("重叠保留：相邻切片在边界处有交集，避免关键句被切断")
    void overlapIsApplied() {
        StringBuilder sb = new StringBuilder("# 文档\n\n");
        for (int i = 0; i < 10; i++) {
            sb.append("段落").append(i).append("：").append("内容".repeat(40)).append("\n\n");
        }
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(sb.toString(), 200, 50);
        assertThat(chunks.size()).isGreaterThan(1);
        // 至少存在一对相邻切片共享内容（重叠生效）
        boolean hasOverlap = false;
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1).content();
            String curr = chunks.get(i).content();
            String prevTail = prev.substring(Math.max(0, prev.length() - 20));
            if (curr.contains(prevTail)) {
                hasOverlap = true;
                break;
            }
        }
        assertThat(hasOverlap).as("相邻切片之间应存在重叠").isTrue();
    }

    @Test
    @DisplayName("边界输入")
    void edgeCases() {
        assertThat(MarkdownChunker.chunk(null, 400, 60)).isEmpty();
        assertThat(MarkdownChunker.chunk("", 400, 60)).isEmpty();
        assertThat(MarkdownChunker.chunk("   \n  ", 400, 60)).isEmpty();
        // 没有标题的纯文本也能切
        assertThat(MarkdownChunker.chunk("一段没有标题的说明文字。", 400, 60)).hasSize(1);
        // 只有标题没有正文
        assertThat(MarkdownChunker.chunk("# 只有标题\n\n## 子标题", 400, 60)).isEmpty();
    }

    @Test
    @DisplayName("真实知识库文档能被正常切分（回归保护）")
    void realDocument() throws Exception {
        String markdown = """
                ---
                docCode: TEST
                title: 测试文档
                ---

                # 入住申请流程

                ## 一、谁可以申请

                所有在职员工因公出差均可申请。需要提供出差单号。

                ## 二、审批时限

                区域管理员应在 24 小时内处理。超过 24 小时系统会自动提醒。

                ## 三、失效规则

                审批通过后，若在入住日次日 23:59 前未办理入住，申请将自动失效并释放床位。
                """;
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(markdown, 400, 60);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.stream().map(MarkdownChunker.Chunk::content).reduce("", String::concat))
                .contains("所有在职员工因公出差均可申请")
                .contains("24 小时")
                .contains("自动失效并释放床位");
    }
}
