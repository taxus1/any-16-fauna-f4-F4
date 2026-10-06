package com.somepro.interfaces.rest.report.converter;

import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.interfaces.rest.common.vo.PageVO;
import com.somepro.interfaces.rest.report.vo.ReportVO;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AbnormalReport（领域）→ ReportVO（对外）转换器（用户接口层）。
 */
public final class ReportVoConverter {

    private ReportVoConverter() {
    }

    public static ReportVO toVo(AbnormalReport report) {
        return new ReportVO(report.getId(), report.getReportNo(), report.getObsId(), report.getSiteId(),
                report.getCategory(), report.getSeverity(), report.getStatus(),
                report.getReportedAt(), report.getHandledAt());
    }

    public static PageVO<ReportVO> toPageVo(PageResult<AbnormalReport> page) {
        List<ReportVO> content = page.content().stream()
                .map(ReportVoConverter::toVo)
                .collect(Collectors.toList());
        return new PageVO<>(content, page.total(), page.pageNum(), page.pageSize(), page.totalPages());
    }
}
