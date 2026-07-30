package com.agentflow.api;

import com.agentflow.observability.ExecutionTrace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 诊断 REST 端点（U6，R12）。
 *
 * <p>POST /api/diagnosis —— 接收 ExecutionTrace.Snapshot JSON，
 * 调用 {@link DiagnosisService} 分析 5 类问题，返回诊断报告。
 */
@RestController
@RequestMapping("/api")
public class DiagnosisController {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisController.class);
    private final DiagnosisService service = new DiagnosisService();

    /**
     * 诊断请求体：ExecutionTrace 的快照数据。
     */
    public record DiagnosisRequest(String workflowId, ExecutionTrace.Snapshot trace) {
    }

    /**
     * 分析工作流执行轨迹，识别 5 类常见问题。
     *
     * @return 诊断报告（问题类型 + 节点 + 描述 + 修复建议）
     */
    @PostMapping("/diagnosis")
    public ResponseEntity<DiagnosisService.DiagnosisReport> diagnose(@RequestBody DiagnosisRequest request) {
        log.info("诊断请求 wf={}", request.workflowId());
        DiagnosisService.DiagnosisReport report = service.diagnose(request.trace());
        log.info("诊断完成 wf={}: totalNodes={}, failed={}, findings={}",
                report.workflowId(), report.totalNodes(), report.failedNodes(), report.findings().size());
        return ResponseEntity.ok(report);
    }
}
