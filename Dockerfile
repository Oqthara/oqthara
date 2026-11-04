# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY oq-runtime oq-runtime
COPY oq-core oq-core
COPY oq-stdlib oq-stdlib
COPY oq-compiler oq-compiler
COPY oq-cli oq-cli
COPY conformance conformance
COPY examples examples

RUN ./gradlew :oq-cli:standaloneJar --no-daemon --console=plain

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/oq-cli/build/libs/oq.jar /app/oq.jar
COPY --from=build /src/examples /app/examples

RUN printf '#!/bin/sh\nexec java -jar /app/oq.jar "$@"\n' > /usr/local/bin/oq \
    && chmod +x /usr/local/bin/oq

WORKDIR /work
ENTRYPOINT ["java", "-jar", "/app/oq.jar"]
CMD ["--version"]
