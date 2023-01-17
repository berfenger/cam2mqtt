FROM alpine:3.17.1

RUN apk add --no-cache tzdata ca-certificates

ARG TARGETPLATFORM

RUN \
    if [ "$TARGETPLATFORM" = "linux/arm/v7" ]; then \
        apk add --no-cache openjdk8-jre; \
    else \
        apk add --no-cache openjdk17-jre; \
    fi

ADD ./target/universal/stage/bin /opt/app/bin
ADD ./target/universal/stage/lib /opt/app/lib

WORKDIR /opt/app

ENV HTTP_PORT=8080
ENV CONFIG=/config.yml

CMD [ "./bin/cam2mqtt" ]
EXPOSE 8080/tcp
