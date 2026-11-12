package com.overseas.dorm.agent.rag;

/**
 * 向量编解码。
 *
 * <h3>为什么用二进制而不是 JSON 存向量</h3>
 * 一个 512 维的 float 向量：
 * <pre>
 *   JSON 数组  [0.123,-0.456,...]   约 6~8 KB（含标点与精度）
 *   float32 小端字节序              512 × 4 = 2048 B
 * </pre>
 * 体积差 3 倍以上。更重要的是检索时的开销：
 * 二进制可以直接 memcpy 进 {@code float[]}，而 JSON 需要逐字符解析 ——
 * 知识库几千个切片时，这个差异是"毫秒级"和"几十毫秒级"的区别。
 *
 * <p>这里用 {@code ByteBuffer} 而不是手写位移，既简洁又能自动处理字节序。
 * <b>统一用 LITTLE_ENDIAN</b>：跨机器迁移数据时若不显式指定字节序，
 * 大端机器上导入的数据会变成一堆乱码值，而且不会报错 —— 只是检索结果全错。
 */
public final class VectorCodec {

    private VectorCodec() {
    }

    public static byte[] encode(float[] vector) {
        if (vector == null) {
            return new byte[0];
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(vector.length * Float.BYTES)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    public static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new float[0];
        }
        if (bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("向量字节长度不是 4 的倍数，数据可能已损坏：" + bytes.length);
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    /**
     * 余弦相似度。
     *
     * <p>BGE 系列模型输出已做 L2 归一化，理论上模长恒为 1，
     * 直接点积即可。但这里仍然除以模长 —— 因为一旦有向量来自其它来源
     * （手工构造、旧版本模型），不做归一化会得到大于 1 的"相似度"，
     * 让 minScore 阈值彻底失真，而这类错误很难被发现。
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
