FROM alpine:3.15.1

RUN apk add --no-cache tzdata ca-certificates bash

RUN \
    if [ "$TARGETARCH" = "linux/arm/v7" ]; then \
        apk add --no-cache openjdk8-jre; \
    else \
        apk add --no-cache openjdk11-jre; \
    fi

ADD ./target/universal/stage /opt/app

WORKDIR /opt/app

ENV HTTP_PORT=8080
ENV CONFIG=/config.yml

CMD [ "./bin/cam2mqtt" ]
EXPOSE 8080/tcp
