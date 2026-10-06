package com.somepro.interfaces.rest.report.vo;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 异常个体上报对外返回对象（VO，用户接口层）—— 不可变 record。上报编号 reportNo 必带，
 * 好跟纸质异常记录本对上号。
 *
 * reportedAt 上报时刻登记时记下；handledAt 处置时刻在首次推进处置（处置中/已救护/已采样/结案）
 * 后才有，已上报、还没开处置时为空。
 */
public record ReportVO(Long id, String reportNo, Long obsId, Long siteId,
                       String category, String severity, String status,
                       LocalDateTime reportedAt, LocalDateTime handledAt) implements Serializable {
}
