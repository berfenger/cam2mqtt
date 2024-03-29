ThisBuild / version := "1.0"

ThisBuild / scalaVersion := "2.13.10"

lazy val root = (project in file("."))
  .settings(
    name := "cam2mqtt",
    idePackagePrefix := Some("net.bfgnet.cam2mqtt")
  )

enablePlugins(JavaAppPackaging, AshScriptPlugin)

Global / excludeLintKeys += idePackagePrefix

val AkkaVersion = "2.6.20"
val AkkaHttpVersion = "10.2.9"
libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.15" % Test,
    "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
    "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
    "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
    "com.lightbend.akka" %% "akka-stream-alpakka-mqtt" % "3.0.4",
    "commons-codec" % "commons-codec" % "1.15",
    "org.jsoup" % "jsoup" % "1.13.1",
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "org.codehaus.jettison" % "jettison" % "1.4.1",
    "com.fasterxml.jackson.core" % "jackson-databind" % "2.11.3",
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.11.3",
    "io.circe" %% "circe-generic-extras" % "0.13.0",
    "io.circe" %% "circe-yaml" % "0.13.1",
)

// add ability to define JVM options by "CAM2MQTT_OPTS" env variable
bashScriptExtraDefines += """if [[ "$CAM2MQTT_OPTS" != "" ]]; then
                            |  addJava "${CAM2MQTT_OPTS}"
                            |fi
                            |if [[ "$DEBUG" = "1" ]]; then
                            |  addJava "-Dlogback.configurationFile=logback-debug.xml"
                            |fi""".stripMargin
