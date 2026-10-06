package com.somepro.interfaces.rest.report.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

/**
 * 异常个体上报登记请求（VO，用户接口层）。
 *
 * 只收「从哪条观测报上来」与「类别」：点位照来源观测抄、严重程度系统按来头算、
 * 编号服务端生成、状态先落已上报，都不由前端给。
 * 前置由应用层与落库事务把关：观测非正常且未作废、类别跟观测健康状态对得上、
 * 任务未取消、同一观测没挂着没作废的上报。
 */
public record ReportCreateRequest(
        @NotNull(message = "来源观测不能为空") Long obsId,
        @NotBlank(message = "上报类别不能为空") String category) implements Serializable {
}
