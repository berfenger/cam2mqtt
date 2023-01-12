FROM alpine:3.15.1

RUN apk add --no-cache unzip

ADD . /app_src

WORKDIR /opt/app

RUN unzip /app_src/target/universal/cam2mqtt*.zip

RUN mv cam2mqtt-* cam2mqtt

FROM alpine:3.15.1

RUN apk add --no-cache openjdk8-jre tzdata ca-certificates

COPY --from=0 /opt/app/cam2mqtt /opt/app/

WORKDIR /opt/app/

ENV HTTP_PORT=8080
ENV CONFIG=/config.yml

CMD [ "./bin/cam2mqtt" ]
EXPOSE 8080/tcp
