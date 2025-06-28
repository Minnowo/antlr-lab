# syntax=docker/dockerfile:1

### Stage 1: Build with Maven
FROM maven:3.9-eclipse-temurin-11 AS builder

WORKDIR /build

COPY pom.xml .
COPY src ./src
COPY resources ./resources
COPY static ./static

RUN mvn clean package -B

### Stage 2: Runtime with slim JDK + system utils
FROM eclipse-temurin:11-jre-jammy

ARG LAB_VERSION=0.4-SNAPSHOT
ENV LAB_VERSION=${LAB_VERSION}

WORKDIR /app

# Install only necessary tools
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ghostscript \
        pdf2svg \
        texlive-extra-utils && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Copy static resources and built jar
COPY --from=builder /build/resources /app/resources
COPY --from=builder /build/static /app/static
COPY --from=builder /build/target/antlr4-lab-*-complete.jar /app/antlr4-lab-complete.jar

EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/antlr4-lab-complete.jar"]
