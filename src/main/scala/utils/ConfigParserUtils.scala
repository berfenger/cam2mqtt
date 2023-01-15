package net.bfgnet.cam2mqtt
package utils

import scala.util.Try

object ConfigParserUtils {

    implicit class MapExt(from: Map[String, Any]) {
        def getBool(key: String): Option[Boolean] =
            Try(from.get(key)).toOption.flatten.filter(_ != null).map(_.toString).filter(v => v == "true" || v == "false").map(v => if (v == "true") true else false)

        def getString(key: String): Option[String] =
            Try(from.get(key)).toOption.flatten.filter(_ != null).map(_.toString).filter(_.nonEmpty)

        def getInt(key: String): Option[Int] = {
            Try(from.get(key)).toOption.flatten.filter(_ != null).flatMap(v => Try(v.toString.toInt).toOption)
        }
    }

    implicit class BooleanOptionExt(from: Option[Boolean]) {
        def orFalse: Boolean = from.getOrElse(false)

        def orTrue: Boolean = from.getOrElse(true)
    }

}
