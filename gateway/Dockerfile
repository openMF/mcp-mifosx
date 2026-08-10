# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
#
# Two-stage build: Maven build -> slim JRE runtime.
#   docker build -t openmf/mifos-copilot-gateway ./gateway

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="Mifos Copilot Gateway" \
      org.opencontainers.image.description="LLM agent gateway for the Mifos X Copilot (wire contract v1, ADR-001)" \
      org.opencontainers.image.licenses="MPL-2.0" \
      org.opencontainers.image.source="https://github.com/openMF/mcp-mifosx"
RUN useradd -r -u 1001 copilot
USER copilot
WORKDIR /app
COPY --from=build /build/target/mifos-copilot-gateway-*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
