# One Dockerfile parameterized by module name; builds that service's bootJar and
# ships it on a slim JRE. In AWS this image lives in ECR (see infra/terraform).
#
#   docker build -f infra/docker/service.Dockerfile \
#     --build-arg MODULE=booking-service -t tocketmaster/booking-service .

FROM gradle:8.14.3-jdk17 AS build
WORKDIR /workspace
ARG MODULE

# cache dependencies first for fast rebuilds
COPY settings.gradle.kts build.gradle.kts ./
COPY common/build.gradle.kts common/build.gradle.kts
COPY services/event-service/build.gradle.kts services/event-service/build.gradle.kts
COPY services/booking-service/build.gradle.kts services/booking-service/build.gradle.kts
COPY services/search-service/build.gradle.kts services/search-service/build.gradle.kts
COPY services/queue-service/build.gradle.kts services/queue-service/build.gradle.kts
RUN gradle --no-daemon :common:compileJava || true

COPY common common
COPY services services
RUN gradle --no-daemon ":services:${MODULE}:bootJar"

FROM eclipse-temurin:17-jre-alpine
ARG MODULE
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /workspace/services/${MODULE}/build/libs/ /app/libs/
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar $(ls /app/libs/*.jar | grep -v plain | head -n1)"]
