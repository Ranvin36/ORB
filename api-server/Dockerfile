FROM gradle:8.5-jdk17-alpine AS builder
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

RUN chmod +x gradlew
COPY . .
RUN ./gradlew build -x test