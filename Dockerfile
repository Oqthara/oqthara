# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY oq-core ./oq-core
COPY oq-runtime ./oq-runtime
COPY oq-stdlib ./oq-stdlib
COPY oq-compiler ./oq-compiler
COPY oq-cli ./oq-cli
COPY oq-lsp ./oq-lsp
# settings.gradle.kts includes every module, so the image build needs the module
# directories to exist even though only the CLI jar is built.
COPY bench-jmh ./bench-jmh

RUN chmod +x gradlew \
    && ./gradlew :oq-cli:standaloneJar --no-daemon --console=plain

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/oq-cli/build/libs/oq.jar ./oq.jar

RUN printf '#!/bin/sh\nexec java -jar /app/oq.jar "$@"\n' > /usr/local/bin/oq \
    && chmod +x /usr/local/bin/oq

WORKDIR /work
ENTRYPOINT ["java", "-jar", "/app/oq.jar"]
CMD ["--version"]
