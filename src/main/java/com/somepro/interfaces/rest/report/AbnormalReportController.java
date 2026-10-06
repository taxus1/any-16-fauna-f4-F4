package com.somepro.interfaces.rest.report;

import com.somepro.application.report.AbnormalReportAppService;
import com.somepro.common.Result;
import com.somepro.interfaces.rest.common.vo.PageVO;
import com.somepro.interfaces.rest.report.converter.ReportVoConverter;
import com.somepro.interfaces.rest.report.vo.ReportCreateRequest;
import com.somepro.interfaces.rest.report.vo.ReportVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 异常个体上报接口（用户接口层）：只做协议适配与 VO 转换，业务编排交给应用层。
 *
 * 处置推进是条单向状态机，动作显式给（handle/rescue/sample/close），
 * 跳级、回退、已结案再推都返回业务失败；上报分页按 点位/类别/严重程度/状态 随意拼，
 * 每行带上报编号。已作废（逻辑删除）的不出现在分页与详情里，底子仍留在库里。
 */
@RestController
@RequestMapping("/api/reports")
public class AbnormalReportController {

    private final AbnormalReportAppService reportAppService;

    public AbnormalReportController(AbnormalReportAppService reportAppService) {
        this.reportAppService = reportAppService;
    }

    /** 登记异常上报：编号 AR-YYYY-NNNN 服务端生成，严重程度系统按来头算。 */
    @PostMapping
    public Mono<Result<ReportVO>> register(@Valid @RequestBody ReportCreateRequest req) {
        return reportAppService.register(req.obsId(), req.category())
                .map(ReportVoConverter::toVo)
                .map(Result::ok);
    }

    @GetMapping("/{id}")
    public Mono<Result<ReportVO>> detail(@PathVariable Long id) {
        return reportAppService.detail(id)
                .map(ReportVoConverter::toVo)
                .map(Result::ok);
    }

    /** 开始处置：已上报 -> 处置中。 */
    @PostMapping("/{id}/handle")
    public Mono<Result<ReportVO>> handle(@PathVariable Long id) {
        return reportAppService.handle(id)
                .map(ReportVoConverter::toVo)
                .map(Result::ok);
    }

    /** 救护：处置中 -> 已救护。 */
    @PostMapping("/{id}/rescue")
    public Mono<Result<ReportVO>> rescue(@PathVariable Long id) {
        return reportAppService.rescue(id)
                .map(ReportVoConverter::toVo)
                .map(Result::ok);
    }

    /** 采样：处置中 -> 已采样。 */
    @PostMapping("/{id}/sample")
    public Mono<Result<ReportVO>> sample(@PathVariable Long id) {
        return reportAppService.sample(id)
                .map(ReportVoConverter::toVo)
                .map(Result::ok);
    }

    /** 结案：已救护/已采样（死亡单处置中）-> 已结案；已结案再推挡回。 */
    @PostMapping("/{id}/close")
    public Mono<Result<ReportVO>> close(@PathVariable Long id) {
        return reportAppService.close(id)
                .map(ReportVoConverter::toVo)
                .map(Result::ok);
    }

    /** 作废：逻辑删除，名单里不再翻到，底子留在库里备查；作废后同一条观测可重新上报。 */
    @PostMapping("/{id}/void")
    public Mono<Result<Void>> voidReport(@PathVariable Long id) {
        return reportAppService.voidReport(id)
                .then(Mono.just(Result.ok()));
    }

    /**
     * 上报分页：siteId/category/severity/status 随意拼，全空翻整份没作废的上报。
     */
    @GetMapping({"", "/list"})
    public Mono<Result<PageVO<ReportVO>>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status) {
        return reportAppService.pageReports(pageNum, pageSize, siteId, category, severity, status)
                .map(ReportVoConverter::toPageVo)
                .map(Result::ok);
    }
}
