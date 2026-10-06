package com.somepro.infrastructure.persistence.report;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.somepro.infrastructure.persistence.obs.po.WildlifeObsPO;
import com.somepro.infrastructure.persistence.report.po.AbnormalReportPO;
import com.somepro.infrastructure.persistence.task.po.PatrolTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 异常个体上报的 MyBatis-Plus Mapper（基础设施层）。
 *
 * 阻塞（JDBC）API，只能在 boundedElastic 线程上调用（见 AbnormalReportRepositoryImpl#blocking）。
 */
@Mapper
public interface AbnormalReportMapper extends BaseMapper<AbnormalReportPO> {

    /**
     * 查指定号段内已用的最大序号（编号生成用）。
     *
     * 自定义 @Select 不会被 @TableLogic 自动拼 del_flag 条件 —— 这是有意的：
     * 已作废上报占用的编号也不复用，一个号永远只归一条上报。
     *
     * @param prefix   编号前缀（如 "AR-2026-"）
     * @param seqStart 序号在编号串中的起始位置（SQL SUBSTRING 从 1 开始，即 prefix 长度 + 1）
     */
    @Select("SELECT MAX(CAST(SUBSTRING(report_no, #{seqStart}) AS UNSIGNED)) "
            + "FROM t_abnormal_report WHERE report_no LIKE CONCAT(#{prefix}, '%')")
    Long selectMaxSeq(@Param("prefix") String prefix, @Param("seqStart") int seqStart);

    /**
     * 落库事务里「锁来源观测行」：SELECT ... FOR UPDATE 锁住该观测，
     * 两个人前后脚给同一条观测递上报时，后到的在这行上排队，等先到的查重+落库提交后
     * 再读到「已有没作废的上报」，从而只落一条。
     *
     * 这里显式查 del_flag（不过 @TableLogic 的方法），作废的观测也要能读到以报对错误。
     */
    @Select("SELECT * FROM t_wildlife_obs WHERE id = #{id} FOR UPDATE")
    WildlifeObsPO selectObsForUpdate(@Param("id") Long id);

    /**
     * 落库事务里「锁巡护任务行」：复核任务没被取消/删除，
     * 挡下「应用层检查通过后、落库前」任务恰好被取消的并发情形。
     */
    @Select("SELECT * FROM t_patrol_task WHERE id = #{id} FOR UPDATE")
    PatrolTaskPO selectTaskForUpdate(@Param("id") Long id);
}
