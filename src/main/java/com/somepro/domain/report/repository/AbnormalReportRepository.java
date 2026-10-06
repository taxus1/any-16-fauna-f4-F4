package com.somepro.domain.report.repository;

import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.shared.model.PageResult;
import reactor.core.publisher.Mono;

/**
 * 异常个体上报的仓储端口（领域层定义，基础设施层实现）。
 */
public interface AbnormalReportRepository {

    /**
     * 立一条上报：reportNo 由实现侧按 AR-YYYY-NNNN 生成，并发撞号自动重取，不甩底层冲突。
     *
     * 唯一性由实现侧在一个事务里把关：同一条观测只挂一条没作废（del_flag=0）的上报，
     * 两个人前后脚一起递也只落一条（锁来源观测行 + 查重，后到的报业务失败）；
     * 同时复核来源观测没作废、挂的巡护任务没取消，挡下「检查后、落库前」的并发作废/取消。
     */
    Mono<AbnormalReport> create(AbnormalReport report);

    /** 按 id 查看没作废的上报（del_flag=0）。已作废的翻不到，返回空。 */
    Mono<AbnormalReport> findById(Long id);

    /**
     * 按原状态条件推进（并发点两下只放行一下）：
     * UPDATE ... SET status/处置时刻（update_time）=? WHERE id=? AND status=? AND del_flag=0。
     *
     * @return 是否真的翻动了一行（false 表示状态已被别人抢先改过，调用方报「状态已变化」）
     */
    Mono<Boolean> advance(AbnormalReport report, String expectedStatus);

    /**
     * 作废：逻辑删除（del_flag=1），名单里不再翻到，底子仍留在库里备查。
     * 已作废的重复作废不动第二下（@TableLogic 带 del_flag=0 条件）。
     */
    Mono<Void> voidReport(Long id);

    /**
     * 条件分页：点位 / 类别 / 严重程度 / 状态随意拼，全空翻整份没作废的上报。
     * 已作废（del_flag=1）的不出现（@TableLogic 自动过滤），每行带上报编号。
     */
    Mono<PageResult<AbnormalReport>> page(int pageNum, int pageSize,
                                          Long siteId, String category, String severity, String status);
}
