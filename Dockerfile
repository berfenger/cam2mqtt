FROM alpine:3.10.3

RUN apk add --no-cache openjdk8-jre

ADD . /app_src
WORKDIR /app_src
RUN ./gradlew build

FROM alpine:3.10.3

RUN apk add unzip openjdk8-jre

COPY --from=0 /app_src/build/distributions/cam2mqtt.zip /cam2mqtt.zip

WORKDIR /usr/src/app

ENV HTTP_PORT=8080
ENV CONFIG=/config.yml

RUN unzip /cam2mqtt.zip
RUN rm /cam2mqtt.zip

WORKDIR /usr/src/app/cam2mqtt

CMD [ "./bin/cam2mqtt" ]
EXPOSE 8080/tcp
