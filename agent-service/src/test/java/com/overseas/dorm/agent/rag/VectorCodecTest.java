package com.overseas.dorm.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量编解码测试。
 *
 * <p>向量以 float32 小端字节序存进 MEDIUMBLOB。这里最需要守住的是
 * <b>字节序一致性</b>：如果编码用大端、解码用小端（或跨机器迁移时不一致），
 * 得到的不再是报错，而是一堆"看起来正常但完全错误"的浮点数，* 检索结果会莫名其妙地全错，且没有任何异常提示。
 */
class VectorCodecTest {

    @Test
    @DisplayName("编解码往返完全一致")
    void roundTrip() {
        float[] vector = {0.1f, -0.25f, 0.0f, 1.0f, -1.0f, 12345.678f, -0.000123f};
        byte[] encoded = VectorCodec.encode(vector);
        assertThat(encoded).hasSize(vector.length * 4);
        assertThat(VectorCodec.decode(encoded)).containsExactly(vector);
    }

    @Test
    @DisplayName("512 维向量（BGE-small-zh）编解码正确，体积为 2048 字节")
    void realisticDimension() {
        float[] vector = new float[512];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) Math.sin(i) * 0.5f;
        }
        byte[] encoded = VectorCodec.encode(vector);
        assertThat(encoded).hasSize(2048);
        float[] decoded = VectorCodec.decode(encoded);
        assertThat(decoded).hasSize(512);
        assertThat(decoded).containsExactly(vector);
    }

    @Test
    @DisplayName("小端字节序：与手工构造的字节序列一致（跨语言/跨机器可复现）")
    void littleEndianLayout() {
        byte[] encoded = VectorCodec.encode(new float[]{1.0f});
        // IEEE-754 单精度 1.0f = 0x3F800000，小端存放为 00 00 80 3F
        assertThat(encoded).containsExactly(0x00, 0x00, (byte) 0x80, 0x3F);
    }

    @Test
    @DisplayName("余弦相似度：自身为 1，正交为 0，反向为 -1")
    void cosine() {
        float[] a = {1, 0, 0};
        float[] b = {0, 1, 0};
        float[] c = {-1, 0, 0};
        assertThat(VectorCodec.cosine(a, a)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(VectorCodec.cosine(a, b)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(VectorCodec.cosine(a, c)).isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("余弦相似度做归一化，未归一化的向量也不会算出 >1 的分数")
    void cosineNormalizesUnnormalizedVectors() {
        float[] a = {3, 4};
        float[] b = {30, 40};
        // 方向相同，不管模长多大，相似度都应为 1
        assertThat(VectorCodec.cosine(a, b)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("维度不一致或空向量：返回 0 而不是抛异常（避免一条脏数据搞崩整个检索）")
    void cosineEdgeCases() {
        assertThat(VectorCodec.cosine(new float[]{1, 2}, new float[]{1, 2, 3})).isZero();
        assertThat(VectorCodec.cosine(new float[0], new float[0])).isZero();
        assertThat(VectorCodec.cosine(null, new float[]{1})).isZero();
        assertThat(VectorCodec.cosine(new float[]{0, 0}, new float[]{1, 1})).isZero();
    }

    @Test
    @DisplayName("损坏的字节数组：长度不是 4 的倍数时明确报错")
    void corruptedData() {
        assertThatThrownBy(() -> VectorCodec.decode(new byte[]{1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("倍数");
        assertThat(VectorCodec.decode(null)).isEmpty();
        assertThat(VectorCodec.decode(new byte[0])).isEmpty();
    }
}
