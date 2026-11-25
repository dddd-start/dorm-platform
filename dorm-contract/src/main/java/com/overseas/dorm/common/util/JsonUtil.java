package com.overseas.dorm.common.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * JSON 工具。
 *
 * <p>「不抛异常」是有意设计：审计日志、工具调用记录、Outbox 载荷等场景
 * 都要求在<b>任何情况下</b>都能把上下文写下去；如果序列化失败还要往外抛，
 * 就会把一个业务异常升级成「审计缺失 + 业务失败」的双重故障。
 */
@Slf4j
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonUtil() {
    }

    /** 序列化；失败返回 null 并记日志。 */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String s) {
            return s;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /** 序列化并限制长度（写审计日志时避免超长字段打爆数据库）。 */
    public static String toJsonLimit(Object obj, int maxLength) {
        String json = toJson(obj);
        if (json == null || json.length() <= maxLength) {
            return json;
        }
        return json.substring(0, maxLength) + "...(truncated)";
    }

    public static <T> T parse(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            log.warn("JSON 解析失败({}): {}", clazz.getSimpleName(), e.getMessage());
            return null;
        }
    }

    public static <T> T parse(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return MAPPER.readValue(json, List.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
