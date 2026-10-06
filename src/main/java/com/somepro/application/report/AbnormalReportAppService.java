package com.somepro.application.report;

import com.somepro.common.exception.BizException;
import com.somepro.domain.obs.model.WildlifeObs;
import com.somepro.domain.obs.repository.WildlifeObsRepository;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.report.repository.AbnormalReportRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.task.model.PatrolTask;
import com.somepro.domain.task.repository.PatrolTaskRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

/**
 * 异常个体上报应用层：编排用例（登记、处置推进、查看、作废、条件分页）。
 *
 * 登记前置（缺一不可）：
 * - 来源观测得存在且没作废，健康状态还得不是正常（受伤 / 死亡 / 疑似疫病）才报得了；
 * - 上报类别得跟观测当时的健康状态一一对得上：伤着的报受伤、死了的报死亡、
 *   疑似疫病的报疑似疫病，串了不收（观测 SUSPECT 对应上报 SUSPECT_DISEASE）；
 * - 观测挂的巡护任务不能是已取消的；
 * - 同一条观测只挂一条没作废的上报：原来那条作废了，再重报才放行
 *   （两个人前后脚一起递由仓储落库事务「锁观测行 + 查重」收口，只落一条）。
 * 严重程度不由前端填，由领域对象照这单的来头算（死亡 / 疑似疫病一律高；
 * 受伤看观测上抄的保护级别快照，国家一级 / 二级算高，其余算中）。
 *
 * 处置推进是条单向状态机，动作由接口显式给出（开始处置 / 救护 / 采样 / 结案），
 * 只准顺着走，跳级、回退、结案再推都挡回；推进走「按原状态条件更新」，
 * 手快或并发点两下只有一下翻得动，处置时刻不二次翻动。
 */
@Service
public class AbnormalReportAppService {

    /** 处置动作：开始处置（已上报 -> 处置中） */
    public static final String ACTION_HANDLE = "HANDLE";
    /** 处置动作：救护（处置中 -> 已救护） */
    public static final String ACTION_RESCUE = "RESCUE";
    /** 处置动作：采样（处置中 -> 已采样） */
    public static final String ACTION_SAMPLE = "SAMPLE";
    /** 处置动作：结案（已救护/已采样/处置中的死亡单 -> 已结案） */
    public static final String ACTION_CLOSE = "CLOSE";

    private final AbnormalReportRepository reportRepository;
    private final WildlifeObsRepository obsRepository;
    private final PatrolTaskRepository taskRepository;

    public AbnormalReportAppService(AbnormalReportRepository reportRepository,
                                    WildlifeObsRepository obsRepository,
                                    PatrolTaskRepository taskRepository) {
        this.reportRepository = reportRepository;
        this.obsRepository = obsRepository;
        this.taskRepository = taskRepository;
    }

    /**
     * 登记一条异常上报。点位不另收前端的，照来源观测上的 siteId 抄；
     * 编号 AR-YYYY-NNNN 与严重程度都不由前端给。
     */
    public Mono<AbnormalReport> register(Long obsId, String category) {
        if (obsId == null) {
            return Mono.error(new BizException("来源观测不能为空"));
        }
        if (category == null || category.isBlank()) {
            return Mono.error(new BizException("上报类别不能为空"));
        }
        String cat = category.trim();
        return obsRepository.findById(obsId)
                .switchIfEmpty(Mono.error(new BizException("观测记录不存在或已作废")))
                .flatMap(obs -> requireReportable(obs, cat)
                        .then(Mono.defer(() -> {
                            AbnormalReport report = AbnormalReport.create(
                                    obs.getId(), obs.getSiteId(), cat,
                                    obs.getHealthStatus(), obs.getProtectionLevel());
                            return reportRepository.create(report);
                        })));
    }

    /** 查看单条没作废的上报（已作废的翻不到，底子仍在库里）。 */
    public Mono<AbnormalReport> detail(Long id) {
        return reportRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("异常上报不存在")));
    }

    /** 开始处置：已上报 -> 处置中。 */
    public Mono<AbnormalReport> handle(Long id) {
        return advance(id, ACTION_HANDLE, AbnormalReport::startHandling);
    }

    /** 救护：处置中 -> 已救护。 */
    public Mono<AbnormalReport> rescue(Long id) {
        return advance(id, ACTION_RESCUE, AbnormalReport::markRescued);
    }

    /** 采样：处置中 -> 已采样。 */
    public Mono<AbnormalReport> sample(Long id) {
        return advance(id, ACTION_SAMPLE, AbnormalReport::markSampled);
    }

    /** 结案：已救护/已采样（死亡单处置中）-> 已结案；已结案的再推挡回。 */
    public Mono<AbnormalReport> close(Long id) {
        return advance(id, ACTION_CLOSE, AbnormalReport::close);
    }

    /** 作废：逻辑删除，名单里不再翻到，底子留在库里备查；作废后同一条观测可重新上报。 */
    public Mono<Void> voidReport(Long id) {
        return reportRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("异常上报不存在")))
                .flatMap(report -> reportRepository.voidReport(report.getId()));
    }

    /** 条件分页：点位/类别/严重程度/状态随意拼，全空翻整份没作废的上报，每行带上报编号。 */
    public Mono<PageResult<AbnormalReport>> pageReports(int pageNum, int pageSize,
                                                        Long siteId, String category,
                                                        String severity, String status) {
        return reportRepository.page(pageNum, pageSize, siteId, trim(category), trim(severity), trim(status));
    }

    /**
     * 登记前置复核（仓储落库事务里还会锁行再兜一遍，这里先给出明确报错、少开事务）：
     * 类别得是三种之一、观测非正常、类别跟健康状态对得上、挂的任务没取消。
     */
    private Mono<Void> requireReportable(WildlifeObs obs, String category) {
        if (!AbnormalReport.isValidCategory(category)) {
            return Mono.error(new BizException("上报类别非法，仅支持 INJURED/DEAD/SUSPECT_DISEASE"));
        }
        String expectedCategory = AbnormalReport.categoryOfHealth(obs.getHealthStatus());
        if (expectedCategory == null) {
            return Mono.error(new BizException("只有健康状态非正常（受伤/死亡/疑似疫病）的观测才能上报异常"));
        }
        if (!expectedCategory.equals(category)) {
            return Mono.error(new BizException("上报类别与观测当时的健康状态不一致，串类不收"));
        }
        return taskRepository.findById(obs.getTaskId())
                // 取消任务时一并逻辑销账（del_flag=1），findById 翻不到，跟「查无此任务」一起挡下
                .switchIfEmpty(Mono.error(new BizException("观测挂的巡护任务不存在或已取消，不能上报异常")))
                .flatMap(task -> {
                    if (PatrolTask.STATUS_CANCELLED.equals(task.getStatus())) {
                        return Mono.error(new BizException("观测挂的巡护任务已取消，不能上报异常"));
                    }
                    return Mono.empty();
                });
    }

    /**
     * 状态推进通用编排：取出当前单 -> 领域状态机判定能不能走这步（顺手记处置时刻）
     * -> 仓储按原状态条件更新（并发只放行一下）-> 回读拿落库后的状态与处置时刻。
     */
    private Mono<AbnormalReport> advance(Long id, String action, Consumer<AbnormalReport> stateChange) {
        return reportRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("异常上报不存在")))
                .flatMap(report -> {
                    String fromStatus = report.getStatus();
                    try {
                        stateChange.accept(report);
                    } catch (BizException e) {
                        return Mono.error(e);
                    }
                    return reportRepository.advance(report, fromStatus)
                            .flatMap(flipped -> flipped
                                    ? reportRepository.findById(id)
                                    : Mono.error(new BizException("上报状态已变化，请刷新后重试")));
                });
    }

    private static String trim(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
