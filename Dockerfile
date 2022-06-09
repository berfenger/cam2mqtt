FROM alpine:3.12.12

RUN apk add --no-cache unzip openjdk8-jre

ADD . /app_src
WORKDIR /app_src
RUN ./gradlew build

WORKDIR /usr/src/app

RUN unzip /app_src/build/distributions/cam2mqtt.zip

FROM alpine:3.12.12

RUN apk add --no-cache openjdk8-jre tzdata ca-certificates

COPY --from=0 /usr/src/app /usr/src/app

WORKDIR /usr/src/app/cam2mqtt

ENV HTTP_PORT=8080
ENV CONFIG=/config.yml

CMD [ "./bin/cam2mqtt" ]
EXPOSE 8080/tcp
