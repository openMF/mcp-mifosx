# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
#
# Two-stage build: Maven build -> slim JRE runtime.
#   docker build -t openmf/agentic-banking-openfn ./agentic-banking-openfn

FROM maven:3.10-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="Agentic Banking OpenFn Gateway" \
      org.opencontainers.image.description="Agentic loan origination gateway: REST API, case state and human review; OpenFn Lightning runs the underwriting jobs" \
      org.opencontainers.image.licenses="MPL-2.0" \
      org.opencontainers.image.source="https://github.com/openMF/mcp-mifosx"
RUN useradd -r -u 1001 gateway
USER gateway
WORKDIR /app
COPY --from=build /build/target/agentic-banking-openfn-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
