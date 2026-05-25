# ---- Stage 1: Builder ------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

# 의존성 캐시 레이어 — gradle 파일만 먼저 복사해서 의존성만 받음
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 후 bootJar 빌드 (테스트는 CI에서 별도 실행 — 이미지 빌드 단축)
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# layered jar 추출 — JVM이 변경되지 않은 라이브러리 레이어를 재사용해 콜드 스타트 단축
RUN mkdir -p /workspace/extracted && \
    java -Djarmode=layertools -jar build/libs/*.jar extract --destination /workspace/extracted

# ---- Stage 2: Runtime ------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl은 healthcheck용 (이미지 크기 증가 최소)
RUN apt-get update && apt-get install -y --no-install-recommends curl tzdata && \
    rm -rf /var/lib/apt/lists/*

ENV TZ=Asia/Seoul \
    JAVA_OPTS="" \
    SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=local

# non-root 실행 (UID/GID 10001) — ECS Fargate task role + readOnlyRootFilesystem 가정
RUN groupadd -g 10001 spring && useradd -m -u 10001 -g spring spring
WORKDIR /app

# layered jar 복사 — dependencies 먼저(거의 안 바뀜), application 마지막(자주 바뀜)
COPY --from=builder --chown=spring:spring /workspace/extracted/dependencies/         ./
COPY --from=builder --chown=spring:spring /workspace/extracted/spring-boot-loader/   ./
COPY --from=builder --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/application/          ./

USER spring:spring

EXPOSE 8080

# Actuator health 엔드포인트 기반 — UP이 아니면 unhealthy
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -fsS http://localhost:${SERVER_PORT}/actuator/health | grep -q '"status":"UP"' || exit 1

# exec form + sh -c로 JAVA_OPTS 전개 허용
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
