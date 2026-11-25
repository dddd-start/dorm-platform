package com.overseas.dorm.agent.tool;

import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.agent.client.DormServiceClient;
import com.overseas.dorm.domain.vo.RegionOptionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 区域编码/名称 → 区域 ID 的解析器。
 *
 * <p>抽成独立组件的原因很直接：申请、订单、政策三个工具类原本各自实现了一份，
 * 三份的匹配规则略有差异（有的只比 code，有的比 code 和 name，有的抛异常、
 * 有的返回 null）。这种"看起来一样、细节不同"的重复最危险，* 用户说"我想去新加坡"，在申请里能识别、在政策问答里识别不了，
 * 而代码评审时很难看出差别。
 *
 * <p>现在统一为：<b>匹配 code 或 name，忽略大小写；匹配不到时由调用方决定
 * 是"报错并给模型纠偏提示"还是"忽略该条件"</b>。
 */
@Component
@RequiredArgsConstructor
public class RegionResolver {

    private final DormServiceClient regionService;

    /**
     * 解析区域，失败返回 null（供"区域是可选条件"的场景使用）。
     */
    public Long resolveOrNull(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            return null;
        }
        return match(regionService.options(), regionCode.trim());
    }

    /**
     * 解析区域，失败时抛出带纠偏提示的异常（供"区域是必填条件"的场景使用）。
     *
     * <p>提示里列出合法取值，让模型能自我纠正，比抛一个它看不懂的
     * {@code IllegalArgumentException} 有效得多。
     */
    public Long resolveRequired(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            throw BizException.forAgent(ResultCode.PARAM_MISSING, "请指定目的地区域",
                    "缺少目的地区域。请调用 listRegions 获取区域编码，或询问用户要去哪个区域。");
        }
        Long id = match(regionService.options(), regionCode.trim());
        if (id == null) {
            String valid = regionService.options().stream()
                    .map(RegionOptionVO::code)
                    .collect(java.util.stream.Collectors.joining(" / "));
            throw BizException.forAgent(ResultCode.REGION_NOT_FOUND, "未找到区域：" + regionCode,
                    "合法的区域编码是 " + valid + "，请先用 listRegions 确认。");
        }
        return id;
    }

    private Long match(List<RegionOptionVO> options, String input) {
        for (RegionOptionVO option : options) {
            if (option.code() != null && option.code().equalsIgnoreCase(input)) {
                return option.id();
            }
            if (option.name() != null && option.name().equals(input)) {
                return option.id();
            }
        }
        return null;
    }
}
