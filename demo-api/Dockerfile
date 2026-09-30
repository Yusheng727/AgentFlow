# demo-api 运行时镜像 —— 合同审查雏形的部署单元
#
# ── 构建前提 ──────────────────────────────────────────────────────────────
# 先在宿主机打出可执行 JAR：
#     mvn -s settings.xml -B -ntp -pl demo-api -am package -DskipTests
# 产出 demo-api/target/demo-api-*.jar（Spring Boot fat jar，依赖在 BOOT-INF/lib）。
# 构建镜像（上下文必须是仓库根，和 docker-compose.yml 里其它服务保持一致）：
#     docker build -f demo-api/Dockerfile -t agentflow-demo-api:local .
#
# ── 两个刻意的取舍 ────────────────────────────────────────────────────────
# 1) 不在镜像里跑 Maven（不是多阶段构建）。本仓 13 个 Maven 模块，容器内首次构建要
#    拉全部依赖，在小内存机器上极易 OOM/超时；而且 JAR 是字节码，与宿主机架构无关，
#    x86 上打、ARM 上跑是安全的。代价：镜像不可从裸 clone 直接复现，需先 mvn package。
# 2) COPY 路径相对「仓库根」而不是相对本文件。docker 不允许 COPY 到构建上下文之外，
#    老的 demo-supplier-risk/Dockerfile 写 `COPY ../target/...` 就死在这条上
#    （报 forbidden path outside the build context）——本文件不复刻那个错误。
#
# ── 为什么用通配符而不是写死版本号 ────────────────────────────────────────
# SNAPSHOT 版本在开发期常动，写死等于埋一个「忘了同步 Dockerfile」的坑。
# 通配不会误抓到 repackage 留下的 demo-api-*.jar.original（模式要求以 .jar 结尾）。
FROM eclipse-temurin:21-jre

# 基础镜像是精简 JRE，不带 curl/wget。健康检查要用 curl，就得显式装上——
# 否则 HEALTHCHECK 永远失败、容器被标 unhealthy，而服务其实是好的。
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY demo-api/target/demo-api-*.jar app.jar

EXPOSE 8080

# 容器内存有限，JVM 默认按宿主机内存估算堆，在小规格实例上会被 OOMKill。
# 按容器上限的 75% 设堆——这是部署到 1-2GB 机器上最常见的那个坑。
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# 健康检查走 actuator：容器「在跑」不等于「能服务」——Spring 启动要十几秒，
# 没有 healthcheck 时编排系统会以为它早就绪了。
#
# start-period 取 90s：同一个 JAR 本机实测启动耗时 15.9s 和 50.4s 两次，波动很大
# （冷启动 / 依赖缓存 / 磁盘）。这个值只决定「多久之后才开始判失败」，调大不会让
# 服务变慢，调小则会在冷启动时把好容器误判成启动失败——所以宁可宽不可紧。
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app.jar"]
