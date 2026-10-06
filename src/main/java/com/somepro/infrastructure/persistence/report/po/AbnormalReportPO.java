package com.somepro.infrastructure.persistence.report.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.somepro.infrastructure.persistence.base.BasePO;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * t_abnormal_report 表的持久化对象（PO，基础设施层）。
 *
 * 只描述「表长什么样」。表里没有专列存「处置时刻」：异常上报除了状态推进没有别的改口，
 * 审计列 update_time（每次推进由 AutoFillMetaObjectHandler 刷新）即处置时刻，
 * 领域上的 handledAt 由转换器从 update_time 回填，建表脚本不另加列。
 */
@Getter
@Setter
@TableName("t_abnormal_report")
public class AbnormalReportPO extends BasePO {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("report_no")
    private String reportNo;

    @TableField("obs_id")
    private Long obsId;

    @TableField("site_id")
    private Long siteId;

    @TableField("category")
    private String category;

    @TableField("severity")
    private String severity;

    @TableField("status")
    private String status;

    @TableField("reported_at")
    private LocalDateTime reportedAt;
}
