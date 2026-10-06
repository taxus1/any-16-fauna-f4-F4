package com.somepro.infrastructure.persistence.report;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.somepro.common.exception.BizException;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.report.repository.AbnormalReportRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.task.model.PatrolTask;
import com.somepro.infrastructure.config.ReactiveOperatorContext;
import com.somepro.infrastructure.persistence.audit.AuditContextHolder;
import com.somepro.infrastructure.persistence.obs.po.WildlifeObsPO;
import com.somepro.infrastructure.persistence.report.converter.AbnormalReportPoConverter;
import com.somepro.infrastructure.persistence.report.po.AbnormalReportPO;
import com.somepro.infrastructure.persistence.support.BizNoGenerator;
import com.somepro.infrastructure.persistence.task.po.PatrolTaskPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 异常个体上报仓储适配器（基础设施层）。
 *
 * 编号分配：reportNo 按 AR-YYYY-NNNN 生成（年份按登记当下，序号 4 位零填充），
 * 并发撞号由 {@link BizNoGenerator} 重试，唯一索引兜底，一个号只落一条。
 *
 * 唯一性（同一条观测只挂一条没作废的上报）在落库事务里强保证：
 * 先 SELECT ... FOR UPDATE 锁住来源观测行，两个请求前后脚递同一条观测时后到的排队，
 * 等先到的查重 + 落库提交后再读到「已有有效上报」并报业务失败 —— 不靠唯一索引兜底这个约束
 * （建表脚本不碰，t_abnormal_report 上没有 (obs_id) 唯一索引，作废后还要能重报），
 * 所以用「锁观测行 + 事务内查重」把竞态口收死。
 *
 * 作废 = @TableLogic 逻辑删除（del_flag=1）：分页翻不到，底子留在表里备查；
 * 作废占用的编号不复用（selectMaxSeq 的自定义 @Select 不拼 del_flag）。
 */
@Repository
public class AbnormalReportRepositoryImpl implements AbnormalReportRepository {

    /** 编号前缀：AR-（完整形如 AR-2026-） */
    private static final String NO_PREFIX = "AR-";

    /** 上报编号序号位数：4 位零填充（AR-2026-0001），超出位数自然扩展，不截断 */
    private static final int SEQ_WIDTH = 4;

    private final AbnormalReportMapper reportMapper;
    private final TransactionTemplate transactionTemplate;

    public AbnormalReportRepositoryImpl(AbnormalReportMapper reportMapper,
                                       PlatformTransactionManager transactionManager) {
        this.reportMapper = reportMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Mono<AbnormalReport> create(AbnormalReport report) {
        return blocking(() -> transactionTemplate.execute(status -> {
            // ① 锁来源观测行（含已作废的，作废的也要读到以报对错误），并发报同一条观测在此排队
            WildlifeObsPO obs = reportMapper.selectObsForUpdate(report.getObsId());
            if (obs == null) {
                throw new BizException("观测记录不存在");
            }
            if (obs.getDelFlag() != null && obs.getDelFlag() == 1) {
                throw new BizException("观测记录已作废，不能再上报异常");
            }
            // ② 锁任务行复核：自定义 @Select 不拼 del_flag，已取消（连带销账）的任务也读得到，
            //    挡下「检查通过后、落库前」任务恰好被取消的并发情形
            PatrolTaskPO task = reportMapper.selectTaskForUpdate(obs.getTaskId());
            if (task == null) {
                throw new BizException("观测挂的巡护任务不存在，不能上报异常");
            }
            if ((task.getDelFlag() != null && task.getDelFlag() == 1)
                    || PatrolTask.STATUS_CANCELLED.equals(task.getStatus())) {
                throw new BizException("观测挂的巡护任务已取消，不能上报异常");
            }
            // ③ 同一观测只准一条没作废的上报；原来那条作废了，这里数不到才放行重报
            long activeCount = reportMapper.selectCount(Wrappers.<AbnormalReportPO>lambdaQuery()
                    .eq(AbnormalReportPO::getObsId, report.getObsId()));
            if (activeCount > 0) {
                throw new BizException("该观测已有一条没作废的异常上报，不能重复上报");
            }
            // ④ 类别跟观测当时健康状态再对一遍（应用层已对，事务内兜底）
            String expected = AbnormalReport.categoryOfHealth(obs.getHealthStatus());
            if (expected == null) {
                throw new BizException("只有健康状态非正常（受伤/死亡/疑似疫病）的观测才能上报异常");
            }
            if (!expected.equals(report.getCategory())) {
                throw new BizException("上报类别与观测当时的健康状态不一致，串类不收");
            }
            // ⑤ 取号落库：撞 report_no 唯一索引在事务内重取号再试
            String prefix = NO_PREFIX + LocalDate.now().getYear() + "-";
            return BizNoGenerator.insertWithRetry(
                    () -> reportMapper.selectMaxSeq(prefix, prefix.length() + 1),
                    prefix,
                    no -> doInsert(report, no),
                    SEQ_WIDTH);
        }));
    }

    @Override
    public Mono<AbnormalReport> findById(Long id) {
        return blocking(() -> {
            AbnormalReportPO po = reportMapper.selectById(id);
            return po == null ? null : AbnormalReportPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<Boolean> advance(AbnormalReport report, String expectedStatus) {
        return blocking(() -> {
            // 条件更新：只有仍处原状态的那一行才翻得动（并发点两下只放行一下，不能跳级/回退）；
            // SET 只带新状态，处置时刻走审计列 update_time（MetaObjectHandler 填当下），
            // del_flag=0 由 @TableLogic 拼上，已作废的翻不动。
            AbnormalReportPO po = new AbnormalReportPO();
            po.setStatus(report.getStatus());
            int rows = reportMapper.update(po, Wrappers.<AbnormalReportPO>lambdaUpdate()
                    .eq(AbnormalReportPO::getId, report.getId())
                    .eq(AbnormalReportPO::getStatus, expectedStatus));
            return rows == 1;
        });
    }

    @Override
    public Mono<Void> voidReport(Long id) {
        return blocking(() -> {
            // @TableLogic 把 deleteById 改写成 UPDATE t_abnormal_report SET del_flag=1
            // WHERE id=? AND del_flag=0：已作废的重复作废不动第二下，底子仍留在表里。
            reportMapper.deleteById(id);
            return Boolean.TRUE;
        }).then();
    }

    @Override
    public Mono<PageResult<AbnormalReport>> page(int pageNum, int pageSize,
                                                 Long siteId, String category,
                                                 String severity, String status) {
        return this.<PageResult<AbnormalReport>>blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                LambdaQueryWrapper<AbnormalReportPO> wrapper = Wrappers.<AbnormalReportPO>lambdaQuery()
                        .eq(siteId != null, AbnormalReportPO::getSiteId, siteId)
                        .eq(hasText(category), AbnormalReportPO::getCategory, category)
                        .eq(hasText(severity), AbnormalReportPO::getSeverity, severity)
                        .eq(hasText(status), AbnormalReportPO::getStatus, status)
                        .orderByAsc(AbnormalReportPO::getId);
                List<AbnormalReportPO> rows = reportMapper.selectList(wrapper);
                long total = rows instanceof com.github.pagehelper.Page
                        ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                        : rows.size();
                List<AbnormalReport> content = rows.stream()
                        .map(AbnormalReportPoConverter::toDomain)
                        .collect(Collectors.toList());
                return new PageResult<>(content, total, pageNum, pageSize);
            } finally {
                PageHelper.clearPage();
            }
        });
    }

    private AbnormalReport doInsert(AbnormalReport report, String reportNo) {
        report.setReportNo(reportNo);
        AbnormalReportPO po = AbnormalReportPoConverter.toPo(report);
        po.setId(IdUtil.getSnowflakeNextId());
        try {
            reportMapper.insert(po);
        } catch (DuplicateKeyException e) {
            // 撞 report_no 唯一索引：抛给 BizNoGenerator 重取号再试（同事务内，MySQL 只回滚该语句）
            throw e;
        }
        return AbnormalReportPoConverter.toDomain(po);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 阻塞 DB 调用 → 响应式链路的桥接器：先取 Reactor Context 里的操作人，
     * 再切到 boundedElastic 执行 JDBC，操作人放进 AuditContextHolder 供审计填充。
     */
    private <T> Mono<T> blocking(Supplier<T> supplier) {
        return Mono.deferContextual(ctx -> {
            String operator = ReactiveOperatorContext.getOperator(ctx);
            return Mono.fromCallable(() -> {
                AuditContextHolder.setOperator(operator);
                try {
                    return supplier.get();
                } finally {
                    AuditContextHolder.clear();
                }
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }
}
