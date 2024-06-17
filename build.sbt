ThisBuild / version := "1.0"

ThisBuild / scalaVersion := "2.13.14"

lazy val root = (project in file("."))
  .settings(
    name := "cam2mqtt",
    idePackagePrefix := Some("net.bfgnet.cam2mqtt")
  )

enablePlugins(JavaAppPackaging, AshScriptPlugin)

Global / excludeLintKeys += idePackagePrefix

val PekkoVersion = "1.0.2"
val PekkoHttpVersion = "1.0.1"
libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.15" % Test,
    "org.apache.pekko" %% "pekko-actor-typed" % PekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % PekkoVersion,
    "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion,
    "org.apache.pekko" %% "pekko-connectors-mqtt" % PekkoVersion,
    "commons-codec" % "commons-codec" % "1.17.0",
    "org.jsoup" % "jsoup" % "1.17.2",
    "ch.qos.logback" % "logback-classic" % "1.5.6",
    "org.codehaus.jettison" % "jettison" % "1.5.4",
    "com.fasterxml.jackson.core" % "jackson-databind" % "2.17.1",
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.17.1",
    "io.circe" %% "circe-generic-extras" % "0.14.3",
    "io.circe" %% "circe-yaml" % "0.14.2",
)

// add ability to define JVM options by "CAM2MQTT_OPTS" env variable
bashScriptExtraDefines += """if [[ "$CAM2MQTT_OPTS" != "" ]]; then
                            |  addJava "${CAM2MQTT_OPTS}"
                            |fi
                            |if [[ "$DEBUG" = "1" ]]; then
                            |  addJava "-Dlogback.configurationFile=logback-debug.xml"
                            |fi""".stripMargin
