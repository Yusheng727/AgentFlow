#!/usr/bin/env python3
"""demo-api 端到端冒烟：提交合同审查工作流 → HITL 暂停 → 批复 → 恢复 → 查轨迹。

验证的是这条链路「真的成立」，不是「服务起来了」：

    提交(202) → 跑到审批门暂停(AWAITING_APPROVAL) → 待批单可见
              → 批复(APPROVE) → 从中断处续跑到 SUCCESS → 轨迹完整

两种用法（覆写已有实例，用于容器/远程验证时用后者）：

    python smoke.py --jar demo-api/target/demo-api-1.0.0-SNAPSHOT.jar
    python smoke.py --url http://localhost:8080

退出码：0 = 全通过；1 = 有线断言失败；2 = 起不来/超时。
"""
import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

# Windows 控制台默认 cp1252，中文和 ✓ 会直接抛 UnicodeEncodeError
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

KEY = "other-demo-key"      # 对应 demo-api application.yml 的 agentflow.api.api-keys

# 一份带审批门的最小合同审查工作流。
# 提交时走 POST /api/workflows，引擎把它当普通 DSL 执行——不需要为它改任何代码。
WORKFLOW = """agentflow:
  version: "1.0"
channels:
  parse:  { reducer: overwrite }
  legal:  { reducer: overwrite }
  report: { reducer: overwrite }
nodes:
  - id: parse
    agent: clause-parser
    prompt_template: "解析合同 ${contractTitle}"
    mock_response: "合同类型：租房；标的：望京西园三区；月租 6500 元"
  - id: legal
    agent: risk-checker
    prompt_template: "分析法律风险：${parse}"
    mock_response: "风险：逾期违约金按日 0.5‰，年化约 18.25%；依据《民法典》第 585 条"
  - id: gate
    agent: approval
  - id: report
    agent: report-generator
    prompt_template: "汇总生成报告：${legal}"
    mock_response: "审查结论：高风险 1 条；建议下调违约金比例后签署"
edges:
  - { from: parse, to: legal }
  - { from: legal, to: gate }
  - { from: gate,  to: report }
"""


class Fail(Exception):
    """断言失败——区别于「服务起不来」。"""


def make_client(base):
    def call(method, path, body=None, timeout=10):
        req = urllib.request.Request(base + path, method=method)
        req.add_header("X-API-Key", KEY)
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, data, timeout=timeout) as r:
                raw = r.read().decode()
                return r.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            raw = e.read().decode()
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, raw
    return call


def wait_health(base, limit=120):
    t0 = time.time()
    while time.time() - t0 < limit:
        try:
            with urllib.request.urlopen(base + "/actuator/health", timeout=3) as r:
                if json.loads(r.read().decode()).get("status") == "UP":
                    return round(time.time() - t0, 1)
        except Exception:
            pass
        time.sleep(1)
    raise SystemExit(f"健康检查 {limit}s 超时：服务没起来")


def wait_status(call, wf, want, limit=30):
    t0 = time.time()
    last = None
    while time.time() - t0 < limit:
        _, s = call("GET", f"/api/workflows/{wf}/status")
        last = (s or {}).get("status")
        if last == want:
            return round(time.time() - t0, 1), s
        time.sleep(0.5)
    raise Fail(f"等待 {want} 超时，最后状态 = {last}")


def run(base):
    call = make_client(base)
    steps = []

    def ok(label, detail):
        steps.append((label, detail))

    ok("服务就绪", f"{wait_health(base)}s")

    code, res = call("POST", "/api/workflows", {
        "workflowName": "contract-review-mvp",
        "version": "1.0",
        "yamlContent": WORKFLOW,
        "inputs": {"contractTitle": "望京西园_租房合同.pdf"},
    })
    if code != 202:
        raise Fail(f"提交应返回 202，实际 {code}：{res}")
    wf = (res or {}).get("workflowId")
    if not wf:
        raise Fail(f"提交没返回 workflowId：{res}")
    ok("提交工作流", f"HTTP 202 · id={wf}")

    dt, st = wait_status(call, wf, "AWAITING_APPROVAL")
    ok("跑至审批门暂停", f"{dt}s → {st.get('status')}")

    _, pend = call("GET", f"/api/workflows/{wf}/approvals/pending")
    if not pend:
        raise Fail("暂停了但拿不到待批单")
    ok("待批单可见", f"{len(pend)} 条")

    code, dec = call("POST", f"/api/workflows/{wf}/approvals/{pend[0]['approvalId']}",
                     {"decision": "APPROVE"})
    if code != 200:
        raise Fail(f"批复应返回 200，实际 {code}：{dec}")
    ok("提交 APPROVE", f"HTTP 200 · {dec.get('workflowStatus')}")

    dt, st = wait_status(call, wf, "SUCCESS")
    ok("从中断处续跑", f"{dt}s → {st.get('status')}")

    _, tr = call("GET", f"/api/workflows/{wf}/trace")
    nodes = sorted((n.get("nodeId"), n.get("step")) for n in (tr or {}).get("nodes", []))
    got = {n for n, _ in nodes}
    missing = {"parse", "legal", "report"} - got
    if missing:
        raise Fail(f"轨迹缺节点：{missing}")
    ok("执行轨迹", " · ".join(f"{n}(step{s})" for n, s in nodes))
    if "gate" not in got:
        ok("⚠ 已知缺口", "审批门未进轨迹——暂停只体现在 status/待批单，不在 trace")

    for label, detail in steps:
        print(f"  ✓ {label}: {detail}")


def main():
    ap = argparse.ArgumentParser()
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--jar", help="本地起这个 JAR 后测（测完自动停）")
    src.add_argument("--url", help="直接测已运行的实例，如 http://localhost:8080")
    a = ap.parse_args()

    if a.url:
        run(a.url.rstrip("/"))
        return

    log = open("demo-api-smoke.log", "wb")
    proc = subprocess.Popen(["java", "-jar", a.jar], stdout=log, stderr=subprocess.STDOUT)
    try:
        run("http://localhost:8080")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
        log.close()


if __name__ == "__main__":
    print("demo-api 冒烟：合同审查 → HITL 暂停 → 批复 → 恢复")
    try:
        main()
    except Fail as e:
        print(f"\n  ✗ 断言失败：{e}")
        sys.exit(1)
    print("\n全部通过。")
