package com.somepro.domain.report.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.obs.model.ObsSummary;
import com.somepro.domain.shared.model.BaseEntity;
import com.somepro.domain.species.model.Species;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 异常个体上报聚合根（领域层）：巡护碰上非正常个体（受伤 / 死亡 / 疑似疫病）单独立的一条单。
 *
 * 业务规则：
 * - 一条上报从哪条观测报上来（obsId）、落在哪个点（siteId，照观测上的点抄，不另收前端的点）；
 * - 类别三选一：{@link #CATEGORY_INJURED 受伤} / {@link #CATEGORY_DEAD 死亡} /
 *   {@link #CATEGORY_SUSPECT_DISEASE 疑似疫病}，必须跟观测当时的健康状态一一对得上，
 *   伤着的只能报受伤、死了的只能报死亡、疑似疫病的只能报疑似疫病，串了不收
 *   （观测健康状态 SUSPECT 对应上报类别 SUSPECT_DISEASE）；
 * - 严重程度不由前端填，系统按这单的来头算：死亡 / 疑似疫病一律 {@link #SEVERITY_HIGH 高}；
 *   受伤看观测上抄的那份保护级别快照，国家一级 / 国家二级算高，其余算 {@link #SEVERITY_MEDIUM 中}；
 * - 新单先落 {@link #STATUS_REPORTED 已上报}，处置单向流转：
 *   已上报 -> {@link #STATUS_HANDLING 处置中} -> {@link #STATUS_RESCUED 已救护} /
 *   {@link #STATUS_SAMPLED 已采样} -> {@link #STATUS_CLOSED 已结案}，
 *   只准顺着走、不准跳级、不准回退；结案是终态，再推一律挡回。
 *
 * 前置（观测是非正常、没作废，类别跟健康状态对得上，挂的任务没取消，同一条观测没挂着没作废的上报）
 * 要查观测 / 任务 / 上报仓储，由应用层与仓储落库事务一起把关；领域对象只保证自身字段与状态机不变量。
 *
 * 编号 reportNo 由仓储层在落库时分配（AR-YYYY-NNNN 式），领域对象只持有不生成。
 * handledAt 是「处置时刻」：每次推进状态时记下当下；表里没有专列，落在审计列 update_time 上
 * （上报单除了状态推进没有别的改口），读侧由仓储适配器回填。
 */
@Getter
@Setter
public class AbnormalReport extends BaseEntity {

    /** 类别：受伤 */
    public static final String CATEGORY_INJURED = "INJURED";
    /** 类别：死亡 */
    public static final String CATEGORY_DEAD = "DEAD";
    /** 类别：疑似疫病 */
    public static final String CATEGORY_SUSPECT_DISEASE = "SUSPECT_DISEASE";

    /** 严重程度：中 */
    public static final String SEVERITY_MEDIUM = "MEDIUM";
    /** 严重程度：高 */
    public static final String SEVERITY_HIGH = "HIGH";

    /** 状态：已上报 */
    public static final String STATUS_REPORTED = "REPORTED";
    /** 状态：处置中 */
    public static final String STATUS_HANDLING = "HANDLING";
    /** 状态：已救护 */
    public static final String STATUS_RESCUED = "RESCUED";
    /** 状态：已采样 */
    public static final String STATUS_SAMPLED = "SAMPLED";
    /** 状态：已结案 */
    public static final String STATUS_CLOSED = "CLOSED";

    private static final Set<String> CATEGORIES = Set.of(
            CATEGORY_INJURED, CATEGORY_DEAD, CATEGORY_SUSPECT_DISEASE);

    /**
     * 处置中往下能走到哪，按这单的类别定。
     */
    private String allowedAfterHandling() {
        if (CATEGORY_INJURED.equals(this.category)) {
            return STATUS_RESCUED;
        }
        if (CATEGORY_SUSPECT_DISEASE.equals(this.category)) {
            return STATUS_SAMPLED;
        }
        // 死亡这一支不救护、不采样，处置完直接结案
        return STATUS_CLOSED;
    }

    private Long id;

    /** 上报编号（如 AR-2026-0001），全局唯一 */
    private String reportNo;

    /** 从哪条观测报上来的（t_wildlife_obs.id），挂上后不改 */
    private Long obsId;

    /** 在哪个点（照观测上的 siteId 抄，不另收前端的点） */
    private Long siteId;

    /** 类别：INJURED / DEAD / SUSPECT_DISEASE，跟观测当时健康状态一一对得上 */
    private String category;

    /** 严重程度：HIGH / MEDIUM，系统按来头算，不由前端填 */
    private String severity;

    /** 状态：REPORTED / HANDLING / RESCUED / SAMPLED / CLOSED */
    private String status;

    /** 上报时刻（登记当下记下） */
    private LocalDateTime reportedAt;

    /** 处置时刻（最近一次推进处置时记下；已上报、还没开处置时为空） */
    private LocalDateTime handledAt;

    /**
     * 工厂方法：立一条异常上报，先落「已上报」。
     *
     * @param obsId             来源观测 id（应用层验过：非正常、没作废、任务没取消、没挂着有效上报）
     * @param siteId            来源观测上的监测点 id
     * @param category          上报类别，必须与观测当时健康状态对得上
     * @param obsHealthStatus   观测当时的健康状态（NORMAL/INJURED/DEAD/SUSPECT）
     * @param protectionLevel   观测上抄的保护级别快照（受伤时据此算严重程度）
     */
    public static AbnormalReport create(Long obsId, Long siteId, String category,
                                        String obsHealthStatus, String protectionLevel) {
        if (obsId == null) {
            throw new BizException("来源观测不能为空");
        }
        if (siteId == null) {
            throw new BizException("监测点不能为空");
        }
        AbnormalReport report = new AbnormalReport();
        report.obsId = obsId;
        report.siteId = siteId;
        report.category = matchCategory(category, obsHealthStatus);
        report.severity = calculateSeverity(report.category, protectionLevel);
        report.status = STATUS_REPORTED;
        report.reportedAt = LocalDateTime.now();
        return report;
    }

    /**
     * 类别得跟观测当时的健康状态对得上：伤着的报受伤、死了的报死亡、疑似疫病的报疑似疫病，
     * 串了不收；正常的观测压根不该走到这里（应用层先挡）。
     */
    public static String matchCategory(String category, String obsHealthStatus) {
        if (category == null || !CATEGORIES.contains(category)) {
            throw new BizException("上报类别非法，仅支持 INJURED/DEAD/SUSPECT_DISEASE");
        }
        String expected = categoryOfHealth(obsHealthStatus);
        if (expected == null) {
            throw new BizException("只有健康状态非正常（受伤/死亡/疑似疫病）的观测才能上报异常");
        }
        if (!expected.equals(category)) {
            throw new BizException("上报类别与观测当时的健康状态不一致，串类不收");
        }
        return category;
    }

    /** 类别是否为三种之一。 */
    public static boolean isValidCategory(String category) {
        return category != null && CATEGORIES.contains(category);
    }

    /** 观测健康状态 -> 上报类别；正常（NORMAL）没有对应类别，返回 null。 */
    public static String categoryOfHealth(String healthStatus) {
        if (ObsSummary.HEALTH_INJURED.equals(healthStatus)) {
            return CATEGORY_INJURED;
        }
        if (ObsSummary.HEALTH_DEAD.equals(healthStatus)) {
            return CATEGORY_DEAD;
        }
        if (ObsSummary.HEALTH_SUSPECT.equals(healthStatus)) {
            return CATEGORY_SUSPECT_DISEASE;
        }
        return null;
    }

    /**
     * 严重程度按这单的来头算，不由前端填：
     * 死亡 / 疑似疫病一律高；受伤看观测上抄的保护级别快照，国家一级 / 国家二级算高，其余算中。
     */
    public static String calculateSeverity(String category, String protectionLevel) {
        if (CATEGORY_DEAD.equals(category) || CATEGORY_SUSPECT_DISEASE.equals(category)) {
            return SEVERITY_HIGH;
        }
        if (Species.LEVEL_NATIONAL_ONE.equals(protectionLevel)
                || Species.LEVEL_NATIONAL_TWO.equals(protectionLevel)) {
            return SEVERITY_HIGH;
        }
        return SEVERITY_MEDIUM;
    }

    /** 已上报 -> 处置中。 */
    public void startHandling() {
        advance(STATUS_HANDLING);
    }

    /** 处置中 -> 已救护（受伤个体救治后走这支）。 */
    public void markRescued() {
        advance(STATUS_RESCUED);
    }

    /** 处置中 -> 已采样（疑似疫病采样送检走这支）。 */
    public void markSampled() {
        advance(STATUS_SAMPLED);
    }

    /** 已救护 / 已采样 / 处置中（死亡那支）-> 已结案。 */
    public void close() {
        advance(STATUS_CLOSED);
    }

    /**
     * 状态推进：只准顺着状态机走一步。跳级（已上报直接救护/采样/结案）、回退、
     * 已结案再推都挡回；处置中往救护还是采样也得跟这单的类别对路。
     *
     * 推进成功时把处置时刻记成当下。
     */
    private void advance(String target) {
        String allowed;
        if (STATUS_REPORTED.equals(this.status)) {
            allowed = STATUS_HANDLING;
        } else if (STATUS_HANDLING.equals(this.status)) {
            allowed = allowedAfterHandling();
        } else if (STATUS_RESCUED.equals(this.status) || STATUS_SAMPLED.equals(this.status)) {
            allowed = STATUS_CLOSED;
        } else if (STATUS_CLOSED.equals(this.status)) {
            throw new BizException("上报已结案，不能再推进处置");
        } else {
            throw new BizException("上报状态非法，不能推进处置");
        }
        if (!allowed.equals(target)) {
            throw new BizException("处置状态不能这样推进：当前 " + this.status + "，不允许到 " + target);
        }
        this.status = target;
        this.handledAt = LocalDateTime.now();
    }
}
