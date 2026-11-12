package com.overseas.dorm.agent.client;

import com.overseas.dorm.agent.security.AgentTokenContext;
import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.enums.ApplySource;
import com.overseas.dorm.common.enums.ApplyStatus;
import com.overseas.dorm.common.enums.AuditEnums;
import com.overseas.dorm.common.enums.GenderLimit;
import com.overseas.dorm.common.enums.OrderStatus;
import com.overseas.dorm.common.enums.RoomType;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.common.trace.TraceContext;
import com.overseas.dorm.domain.dto.ApplyCancelRequest;
import com.overseas.dorm.domain.dto.ApplyCreateRequest;
import com.overseas.dorm.domain.dto.CheckInRequest;
import com.overseas.dorm.domain.dto.CheckOutRequest;
import com.overseas.dorm.domain.dto.RoomAvailabilityRow;
import com.overseas.dorm.domain.entity.DormBuilding;
import com.overseas.dorm.domain.entity.DormRoom;
import com.overseas.dorm.domain.entity.Region;
import com.overseas.dorm.domain.entity.StayOrder;
import com.overseas.dorm.domain.vo.ApplyVO;
import com.overseas.dorm.domain.vo.BedSummaryVO;
import com.overseas.dorm.domain.vo.BuildingOptionVO;
import com.overseas.dorm.domain.vo.OccupancyVO;
import com.overseas.dorm.domain.vo.OrderVO;
import com.overseas.dorm.domain.vo.RegionOptionVO;
import com.overseas.dorm.domain.vo.RegionVO;
import com.overseas.dorm.domain.vo.RoomStatsVO;
import com.overseas.dorm.domain.vo.StatsVO;
import com.overseas.dorm.domain.vo.UserVO;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 业务服务客户端 —— Agent 服务访问业务数据的唯一入口。
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>不连业务库</b>：Agent 只拥有 Agent 库，所有业务读写都通过这里；</li>
 *   <li><b>身份透传</b>：请求携带当前用户的 JWT（{@link AgentTokenContext}），
 *       由 dorm-service 重新解析并校验会话，Agent 不自行声明 userId；</li>
 *   <li><b>错误还原</b>：把 {@code Result.code} 还原成 {@link ResultCode}，
 *       工具层据此给出与单体时代一致的纠偏提示（例如床位被占用的 3005）。</li>
 * </ul>
 *
 * <p>这类客户端的注释要写清“边界”而不是“调用细节”：
 * 一旦有人图方便在这里加一个直连数据库的方法，整个拆分就名存实亡了。
 */
@Slf4j
@Component
public class DormServiceClient {

    private final RestClient restClient;
    private final String internalToken;
    /**
     * 业务审计专用执行器：审计是观测数据，不能阻塞用户拿到 DONE。
     * 用虚拟线程而不是共享线程池，避免审计写满队列后反过来影响对话。
     */
    private final ExecutorService auditExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public DormServiceClient(@Value("${dorm.service.url:http://127.0.0.1:8081}") String baseUrl,
                             @Value("${dorm.service.timeout-seconds:10}") int timeoutSeconds,
                             @Value("${dorm.internal.token:}") String internalToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.internalToken = internalToken;
    }

    // ================================================================ 区域

    public List<RegionOptionVO> regionOptions() {
        return get("/internal/agent/regions/options", null, new ParameterizedTypeReference<>() {
        });
    }

    /** 启动期可用的服务级目录（不依赖用户请求）。 */
    public List<RegionOptionVO> catalogRegionOptions() {
        return get("/internal/catalog/regions/options", null, new ParameterizedTypeReference<>() {
        });
    }

    public List<RegionVO> regions(Integer status, boolean withStats) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", status);
        params.put("withStats", withStats);
        return get("/internal/agent/regions", params, new ParameterizedTypeReference<>() {
        });
    }

    public Region requireRegion(Long id) {
        return get("/internal/agent/regions/" + id, null, new ParameterizedTypeReference<>() {
        });
    }

    /** 兼容原 RegionService 方法名。 */
    public Region requireEnabled(Long id) {
        return requireRegion(id);
    }

    public List<RegionVO> list(Integer status, boolean withStats) {
        return regions(status, withStats);
    }

    public List<RegionOptionVO> options() {
        return regionOptions();
    }

    public BedSummaryVO regionBedSummary(Long regionId) {
        return get("/internal/agent/regions/" + regionId + "/beds/summary", null,
                new ParameterizedTypeReference<>() {
                });
    }

    public BedSummaryVO bedSummary(Long regionId) {
        return regionBedSummary(regionId);
    }

    // ================================================================ 公寓

    public List<BuildingOptionVO> options(Long regionId) {
        return buildingOptions(regionId);
    }

    public List<BuildingOptionVO> buildingOptions(Long regionId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("regionId", regionId);
        return get("/internal/agent/buildings/options", params, new ParameterizedTypeReference<>() {
        });
    }

    public Map<Long, DormBuilding> buildingMap(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ids", String.join(",", ids.stream().map(String::valueOf).toList()));
        List<DormBuilding> list = get("/internal/agent/buildings/map", params,
                new ParameterizedTypeReference<>() {
                });
        return list.stream().collect(Collectors.toMap(DormBuilding::getId, Function.identity(),
                (a, b) -> a));
    }

    // ================================================================ 房间 / 房源

    public DormRoom requireRoom(Long id) {
        return get("/internal/agent/rooms/" + id, null, new ParameterizedTypeReference<>() {
        });
    }

    public List<RoomAvailabilityRow.BedDetail> bedDetails(Long roomId) {
        return get("/internal/agent/rooms/" + roomId + "/beds", null,
                new ParameterizedTypeReference<>() {
                });
    }

    public List<RoomAvailabilityRow> searchAvailability(Long regionId, Long buildingId, String city,
                                                        RoomType roomType, GenderLimit gender,
                                                        LocalDate checkIn, LocalDate checkOut,
                                                        int minBeds) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("regionId", regionId);
        params.put("buildingId", buildingId);
        params.put("city", city);
        params.put("roomType", roomType);
        params.put("gender", gender);
        params.put("checkIn", checkIn);
        params.put("checkOut", checkOut);
        params.put("minBeds", minBeds);
        return get("/internal/agent/rooms/availability", params, new ParameterizedTypeReference<>() {
        });
    }

    public List<RoomStatsVO> statisticsByRegion(Long regionId) {
        return roomStatistics(regionId);
    }

    public List<RoomStatsVO> roomStatistics(Long regionId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("regionId", regionId);
        return get("/internal/agent/rooms/statistics", params, new ParameterizedTypeReference<>() {
        });
    }

    // ================================================================ 申请

    public PageResult<ApplyVO> applyPage(long current, long size, ApplyStatus status,
                                         Long regionId, Long employeeId, String keyword,
                                         LocalDate from, LocalDate to) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("current", current);
        params.put("size", size);
        params.put("status", status);
        params.put("regionId", regionId);
        params.put("employeeId", employeeId);
        params.put("keyword", keyword);
        params.put("from", from);
        params.put("to", to);
        return get("/internal/agent/applies", params, new ParameterizedTypeReference<Result<PageResult<ApplyVO>>>() {
        });
    }

    public List<ApplyVO> upcomingApplies() {
        return get("/internal/agent/applies/upcoming", null, new ParameterizedTypeReference<>() {
        });
    }

    public ApplyVO applyDetail(Long id) {
        return get("/internal/agent/applies/" + id, null, new ParameterizedTypeReference<>() {
        });
    }

    public ApplyVO createApply(ApplyCreateRequest request) {
        return post("/internal/agent/applies", request, new ParameterizedTypeReference<>() {
        });
    }

    public ApplyVO cancelApply(ApplyCancelRequest request) {
        return post("/internal/agent/applies/" + request.applyId() + "/cancel",
                Map.of("reason", request.reason() == null ? "" : request.reason()),
                new ParameterizedTypeReference<>() {
                });
    }

    public ApplyVO checkIn(CheckInRequest request) {
        return post("/internal/agent/applies/check-in", request,
                new ParameterizedTypeReference<>() {
                });
    }

    public ApplyVO checkOut(CheckOutRequest request) {
        return post("/internal/agent/applies/check-out", request,
                new ParameterizedTypeReference<>() {
                });
    }

    // ================================================================ 订单 / 统计

    public List<OrderVO> myOrders() {
        return get("/internal/agent/orders/mine", null, new ParameterizedTypeReference<>() {
        });
    }

    public OrderVO orderDetail(Long id) {
        return get("/internal/agent/orders/" + id, null, new ParameterizedTypeReference<>() {
        });
    }

    public PageResult<OrderVO> orderPage(long current, long size, OrderStatus status,
                                         Long regionId, Long employeeId, String keyword,
                                         LocalDate from, LocalDate to) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("current", current);
        params.put("size", size);
        params.put("status", status);
        params.put("regionId", regionId);
        params.put("employeeId", employeeId);
        params.put("keyword", keyword);
        params.put("from", from);
        params.put("to", to);
        return get("/internal/agent/orders", params, new ParameterizedTypeReference<Result<PageResult<OrderVO>>>() {
        });
    }

    public List<StayOrder> overdueOrders(int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("limit", limit);
        return get("/internal/agent/orders/overdue", params, new ParameterizedTypeReference<>() {
        });
    }

    public StatsVO overview(Long regionId, int trendDays) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("regionId", regionId);
        params.put("trendDays", trendDays);
        return get("/internal/agent/stats/overview", params, new ParameterizedTypeReference<>() {
        });
    }

    public OccupancyVO occupancy(Long regionId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("regionId", regionId);
        return get("/internal/agent/stats/occupancy", params, new ParameterizedTypeReference<>() {
        });
    }

    // ================================================================ 身份 / 审计

    public UserVO currentUser() {
        return get("/internal/agent/profile", null, new ParameterizedTypeReference<>() {
        });
    }

    /** 兼容原 AuthService 的签名；Agent 场景永远不返回明文手机号。 */
    public UserVO currentUser(boolean withPlainPhone) {
        return currentUser();
    }

    public void audit(boolean success, AuditEnums.Module module, String action,
                      String targetType, Object targetId, String errorMsg) {
        audit(success, module, action, targetType, targetId, errorMsg, null);
    }

    /**
     * 异步写业务审计。
     *
     * <p>为什么必须异步：轮次收尾（DONE/INTERRUPTED）会调用它，
     * 若业务服务网络异常，同步 HTTP 会把“用户看到结束”的时间拖到超时之后。
     * 审计是尽力而为的观测数据，不能反过来决定对话何时结束。
     *
     * <p>令牌/链路上下文在提交时捕获 —— 异步线程不会继承 ThreadLocal。
     */
    public void audit(boolean success, AuditEnums.Module module, String action,
                      String targetType, Object targetId, String errorMsg,
                      Map<String, Object> detail) {
        audit(success, module, action, targetType, targetId, errorMsg, detail, null);
    }

    public void audit(boolean success, AuditEnums.Module module, String action,
                      String targetType, Object targetId, String errorMsg,
                      Map<String, Object> detail, Long costMs) {
        String token = AgentTokenContext.get();
        String traceId = TraceContext.getOrCreate();
        auditExecutor.submit(() -> AgentTokenContext.runAs(token,
                () -> TraceContext.runWith(traceId, () -> {
                    try {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("success", success);
                        body.put("module", module.name());
                        body.put("action", action);
                        body.put("targetType", targetType == null ? "" : targetType);
                        body.put("targetId", targetId == null ? "" : String.valueOf(targetId));
                        body.put("errorMsg", errorMsg == null ? "" : errorMsg);
                        if (detail != null) {
                            body.put("detail", detail);
                        }
                        if (costMs != null) {
                            body.put("costMs", costMs);
                        }
                        post("/internal/agent/audit", body,
                                new ParameterizedTypeReference<Result<Void>>() {
                                });
                    } catch (Exception e) {
                        // 审计是尽力而为：业务服务短暂不可用不应影响对话本身
                        log.warn("[Agent] 业务审计写入失败：{}", e.getMessage());
                    }
                })));
    }

    @PreDestroy
    public void shutdown() {
        auditExecutor.shutdown();
    }

    // ================================================================ HTTP 基础设施

    private <T> T get(String path, Map<String, Object> params,
                      ParameterizedTypeReference<Result<T>> type) {
        try {
            Result<T> body = restClient.get()
                    .uri(uri(path, params))
                    .headers(this::authHeaders)
                    .retrieve()
                    .body(type);
            return unwrap(body);
        } catch (RestClientResponseException e) {
            throw remoteError(e);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Agent] 调用业务服务失败 GET {}：{}", path, e.getMessage());
            throw BizException.of(ResultCode.SYSTEM_ERROR, "业务服务暂时不可用，请稍后再试。");
        }
    }

    private <T> T post(String path, Object payload, ParameterizedTypeReference<Result<T>> type) {
        try {
            Result<T> body = restClient.post()
                    .uri(uri(path, null))
                    .headers(this::authHeaders)
                    .body(payload)
                    .retrieve()
                    .body(type);
            return unwrap(body);
        } catch (RestClientResponseException e) {
            throw remoteError(e);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Agent] 调用业务服务失败 POST {}：{}", path, e.getMessage());
            throw BizException.of(ResultCode.SYSTEM_ERROR, "业务服务暂时不可用，请稍后再试。");
        }
    }

    private void authHeaders(org.springframework.http.HttpHeaders headers) {
        String token = AgentTokenContext.get();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
        headers.set("X-Internal-Token", internalToken == null ? "" : internalToken);
        String traceId = TraceContext.getOrCreate();
        headers.set(TraceContext.HEADER, traceId);
    }

    private static String uri(String path, Map<String, Object> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        if (params != null) {
            params.forEach((k, v) -> {
                if (v != null && !(v instanceof String s && s.isBlank())) {
                    builder.queryParam(k, v);
                }
            });
        }
        return builder.build().encode().toUriString();
    }

    private static <T> T unwrap(Result<T> body) {
        if (body == null) {
            throw BizException.of(ResultCode.SYSTEM_ERROR, "业务服务返回空响应");
        }
        if (!body.isSuccess()) {
            throw BizException.of(ResultCode.fromCode(body.getCode()), body.getMessage());
        }
        return body.getData();
    }

    private static BizException remoteError(RestClientResponseException e) {
        log.error("[Agent] 业务服务 HTTP {}：{}", e.getStatusCode(), e.getResponseBodyAsString());
        return BizException.of(ResultCode.SYSTEM_ERROR,
                "业务服务调用失败（HTTP " + e.getStatusCode().value() + "）");
    }
}
