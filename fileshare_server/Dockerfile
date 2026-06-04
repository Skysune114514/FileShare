# =============================================================================
# FileShare 后端镜像（Spring Boot 3.2 / Java 17 + LibreOffice headless）
# 默认用官方 Docker Hub 基础镜像；国内网络下由 docker-compose 的 REGISTRY_PREFIX
# 统一注入镜像仓库前缀（如 docker.m.daocloud.io/library/），一行即可切换。
# apt 走阿里云镜像、Maven 走阿里云仓库，构建无需代理。
# =============================================================================

# 基础镜像来源参数（声明在首个 FROM 之前，可被所有 FROM 使用）。
# 默认官方；compose 会通过 build args 覆盖为「前缀 + 官方名」。
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-17
ARG JRE_IMAGE=eclipse-temurin:17-jre

# ---------- 阶段 1：编译打包（Maven + JDK 17） ----------
FROM ${MAVEN_IMAGE} AS build
WORKDIR /build

# Maven 换阿里云仓库：依赖下载走国内，无需代理。
RUN mkdir -p /root/.m2 \
 && printf '%s\n' \
      '<settings>' \
      '  <mirrors>' \
      '    <mirror>' \
      '      <id>aliyunmaven</id>' \
      '      <mirrorOf>*</mirrorOf>' \
      '      <name>aliyun public</name>' \
      '      <url>https://maven.aliyun.com/repository/public</url>' \
      '    </mirror>' \
      '  </mirrors>' \
      '</settings>' > /root/.m2/settings.xml

# 先只拷 pom 并预拉依赖，pom 未变时命中 Docker 层缓存，加速重复构建。
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline -DskipTests || true
# 再拷源码真正打包；-DskipTests 跳过测试（测试需 H2，避免无谓拉包与耗时）。
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---------- 阶段 2：运行时（JRE17 + 精简 LibreOffice） ----------
# eclipse-temurin:17-jre 基于 Ubuntu，可用 apt 装 LibreOffice，且自带 glibc，兼容 soffice。
FROM ${JRE_IMAGE}

ENV DEBIAN_FRONTEND=noninteractive \
    TZ=Asia/Shanghai \
    # UseContainerSupport:让 JVM 感知容器内存上限；MaxRAMPercentage=50 使堆约占容器内存一半，
    # 其余留给 soffice 转换子进程与系统，避免堆顶满导致 OOM。
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=50.0 -XX:+UseSerialGC"

# apt 换阿里云镜像（同时兼容 jammy 的 sources.list 与 noble 的 ubuntu.sources 两种格式），
# 再精简安装 LibreOffice：只装转 PDF 必需的 writer/calc/impress + 中文字体（防止中文变方框）。
# fontconfig 保证字体被 soffice 扫描到；--no-install-recommends 大幅减小体积。
RUN set -eux; \
    for f in /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; do \
        if [ -f "$f" ]; then \
            sed -i \
              -e 's|http://archive.ubuntu.com|http://mirrors.aliyun.com|g' \
              -e 's|http://security.ubuntu.com|http://mirrors.aliyun.com|g' \
              -e 's|http://ports.ubuntu.com|http://mirrors.aliyun.com|g' \
              "$f"; \
        fi; \
    done; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        fontconfig \
        fonts-noto-cjk \
        libreoffice-writer \
        libreoffice-calc \
        libreoffice-impress; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/target/*.jar /app/app.jar

# 物理文件 + 预览缓存统一落在该卷（file.storage.root 由 compose 指到这里）。
VOLUME ["/data/file-storage"]

# Spring Boot 内嵌 Tomcat 默认端口 9090。
EXPOSE 9090

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
