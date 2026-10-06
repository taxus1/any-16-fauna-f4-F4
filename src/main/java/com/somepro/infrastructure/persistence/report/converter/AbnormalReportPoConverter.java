package com.somepro.infrastructure.persistence.report.converter;

import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.infrastructure.persistence.report.po.AbnormalReportPO;

/**
 * AbnormalReportPO（表）↔ AbnormalReport（领域）转换器（基础设施层）。
 *
 * 处置时刻没有专列：已上报（REPORTED）还没开过处置，handledAt 为空；
 * 一旦往处置中/已救护/已采样/已结案推进，审计列 update_time 随状态推进刷新，
 * 读侧把它回填成领域上的 handledAt（最近一次处置时刻）。
 */
public final class AbnormalReportPoConverter {

    private AbnormalReportPoConverter() {
    }

    public static AbnormalReportPO toPo(AbnormalReport domain) {
        AbnormalReportPO po = new AbnormalReportPO();
        po.setId(domain.getId());
        po.setReportNo(domain.getReportNo());
        po.setObsId(domain.getObsId());
        po.setSiteId(domain.getSiteId());
        po.setCategory(domain.getCategory());
        po.setSeverity(domain.getSeverity());
        po.setStatus(domain.getStatus());
        po.setReportedAt(domain.getReportedAt());
        po.setDelFlag(domain.getDelFlag());
        po.setCreateBy(domain.getCreateBy());
        po.setCreateTime(domain.getCreateTime());
        po.setUpdateBy(domain.getUpdateBy());
        po.setUpdateTime(domain.getUpdateTime());
        return po;
    }

    public static AbnormalReport toDomain(AbnormalReportPO po) {
        AbnormalReport domain = new AbnormalReport();
        domain.setId(po.getId());
        domain.setReportNo(po.getReportNo());
        domain.setObsId(po.getObsId());
        domain.setSiteId(po.getSiteId());
        domain.setCategory(po.getCategory());
        domain.setSeverity(po.getSeverity());
        domain.setStatus(po.getStatus());
        domain.setReportedAt(po.getReportedAt());
        // 已上报还没开过处置：不把建单时的审计 update_time 当成处置时刻
        domain.setHandledAt(AbnormalReport.STATUS_REPORTED.equals(po.getStatus()) ? null : po.getUpdateTime());
        domain.setDelFlag(po.getDelFlag());
        domain.setCreateBy(po.getCreateBy());
        domain.setCreateTime(po.getCreateTime());
        domain.setUpdateBy(po.getUpdateBy());
        domain.setUpdateTime(po.getUpdateTime());
        return domain;
    }
}
