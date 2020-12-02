FROM alpine:3.10.3

RUN apk add --no-cache unzip openjdk8-jre

ADD . /app_src
WORKDIR /app_src
RUN ./gradlew build

WORKDIR /usr/src/app

ENV HTTP_PORT=8080
ENV CONFIG=/config.yml

RUN unzip /app_src/build/distributions/cam2mqtt.zip
RUN rm -rf /app_src

WORKDIR /usr/src/app/cam2mqtt

CMD [ "./bin/cam2mqtt" ]
EXPOSE 8080/tcp
