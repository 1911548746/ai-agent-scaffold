# 构建阶段固定 Java 17：镜像可以从仓库根目录独立构建，无需预先生成本地 jar。
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY . .
RUN mvn -B -pl ai-agent-scaffold-app -am -DskipTests package

# 运行阶段只保留受支持的 Java 17 JRE 和健康检查所需的 curl，避免携带 Maven 与源码。
FROM eclipse-temurin:17-jre-jammy

LABEL org.opencontainers.image.title="ai-agent-scaffold-app" \
      org.opencontainers.image.description="AI Agent Scaffold Spring Boot application"

ENV TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"

# 容器内 healthcheck 使用业务端口 /readyz；安装完成后删除 apt 缓存以控制运行镜像体积。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --create-home --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/ai-agent-scaffold-app/target/ai-agent-scaffold-app.jar /app/app.jar

# 应用及其依赖不需要 root 权限；避免运行时漏洞直接获得容器 root 身份。
USER app:app

EXPOSE 8080
STOPSIGNAL SIGTERM

# /readyz 位于业务端口，即使管理端口只对本机监听也能被容器编排平台探测。
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${SERVER_PORT:-8080}/readyz" || exit 1

# 使用 exec 形式让 Docker 的 SIGTERM 直接送达 JVM，从而触发 Spring 的优雅停机流程。
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
