# ---- build ----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies separately from source so code edits don't re-download.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- run ------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 10001 appuser
COPY --from=build /build/target/intuitive-search-1.0.0.jar app.jar
USER appuser

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
