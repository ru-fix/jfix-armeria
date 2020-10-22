FROM adoptopenjdk/openjdk11:alpine

LABEL maintainer="Timur Kasatkin <t.kasatkin.o@gmail.com>"
LABEL version="v1"

RUN apk update && \
    apk add --no-cache bash && \
    apk add --no-cache curl && \
    apk add --no-cache busybox-extras && \
    apk add --no-cache fontconfig ttf-dejavu && \
    apk add --no-cache tzdata && \
    rm -rf /var/cache/apk/*
ENV TZ=Europe/Moscow

COPY ./build/libs/test-webflux-server.jar test-webflux-server.jar

ENTRYPOINT ["java", "-jar", "test-webflux-server.jar", "--add-modules java.se \
    --add-exports java.base/jdk.internal.ref=ALL-UNNAMED \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.nio=ALL-UNNAMED \
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.management/sun.management=ALL-UNNAMED \
    --add-opens jdk.management/com.sun.management.internal=ALL-UNNAMED"]
