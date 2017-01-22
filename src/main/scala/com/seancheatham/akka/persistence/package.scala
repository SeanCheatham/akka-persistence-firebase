package com.seancheatham.akka

import akka.serialization.Serialization
import com.google.common.io.BaseEncoding
import play.api.libs.json._

package object persistence {

  /**
    * Converts an "Any" to a JsObject
    *
    * @param event         The event to serialize
    * @param serialization An implicit serialization from the actor system
    * @return a JsObject with:
    *         {
    *           "vt" -> "int" | "long" | "float" | "double" | "boolean" | "string" | Serialization ID,
    *           "v" -> The Serialized Value
    *         }
    */
  def anyToJson(event: Any)(implicit serialization: Serialization): JsObject = {
    val (typeName, json) =
      anyToSerialized(event)
    Json.obj("vt" -> typeName, "v" -> json)
  }

  protected[persistence] def anyToSerialized(any: Any)(implicit serialization: Serialization): (String, JsValue) = {
    any match {
      case i: Int => ("int", JsNumber(i))
      case i: Long => ("long", JsNumber(i))
      case i: Float => ("float", JsNumber(i.toDouble))
      case i: Double => ("double", JsNumber(i))
      case i: Boolean => ("boolean", JsBoolean(i))
      case i: String => ("string", JsString(i))
      case i: AnyRef =>
        val serializer = serialization.findSerializerFor(i)
        val serialized = BaseEncoding.base64().encode(serializer.toBinary(i))
        (serializer.identifier.toString, JsString(serialized))
    }
  }

  /**
    * Reads a Json value into an "Any"
    *
    * @param v a JsObject with:
    *          {
    *          "vt" -> "int" | "long" | "float" | "double" | "boolean" | "string" | Serialization ID,
    *          "v" -> The Serialized Value
    *          }
    * @return The proper deserialized value
    */
  def jsonToAny(v: JsValue)(implicit serialization: Serialization): Any =
    serializedToAny((v \ "vt").as[String], (v \ "v").get)

  protected[persistence] def serializedToAny(typeName: String, json: JsValue)(implicit serialization: Serialization): Any = {
    typeName match {
      case "int" => json.as[Int]
      case "long" => json.as[Long]
      case "float" => json.as[Float]
      case "double" => json.as[Double]
      case "boolean" => json.as[Boolean]
      case "string" => json.as[String]
      case t =>
        val decoded = BaseEncoding.base64().decode(json.as[String])
        serialization.deserialize(decoded, t.toInt, None).get
    }
  }
  
}
