package com.seancheatham.akka.persistence

import java.util.concurrent.Executors

import akka.actor.{ActorLogging, ExtendedActorSystem}
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.{AtomicWrite, PersistentRepr}
import akka.serialization.{Serialization, SerializationExtension}
import com.google.common.io.BaseEncoding
import com.google.firebase.database.{DataSnapshot, DatabaseError, ValueEventListener}
import com.seancheatham.storage.firebase.FirebaseDatabase
import com.typesafe.config.Config
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Success, Try}

/**
  * An Akka Persistence Journaling system which is backed by a Google Firebase instance.  Firebase provides several
  * features, and among them are a Realtime Database.  Their database is document-based, meaning it is effectively
  * modeled as a giant JSON object, thus allowing for an expressive way of interacting with it.  The true power, however,
  * comes from its "realtime"-ness.  Firebase is designed for high-performance reads and writes, as well as for listening
  * on real-time connections.  This makes it effective for Akka Journaling.
  */
class FirebaseJournal(config: Config) extends AsyncWriteJournal with ActorLogging {

  /**
    * Akka documentation doesn't recommend using the actor system's execution context,
    * so create a custom one for this journal.
    */
  private implicit val ec =
    ExecutionContext.fromExecutor(
      Executors.newFixedThreadPool(2)
    )

  /**
    * The base path in within the context of Firebase to write Actor journals.  For example, events for persistence ID "X"
    * will be written to `{persistenceKeyPath}/X/events`
    */
  val persistenceKeyPath: String =
    config.getString("base_key_path")

  /**
    * The ActorSystem to which this journal's Actor belongs
    */
  private implicit val actorSystem =
    persistence.system

  /**
    * An implicit Serialization used when reading/writing events in Firebase
    */
  private implicit val serialization: Serialization =
    SerializationExtension(actorSystem)

  /**
    * Appends the given messages to the `{persistenceKeyPath}/{persistenceId}/events/{seqNr}` path
    */
  def asyncWriteMessages(messages: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] = {
    def writeRepr(repr: PersistentRepr,
                  persistenceId: String) =
      FirebaseDatabase().write(
        persistenceKeyPath,
        persistenceId,
        "events",
        repr.sequenceNr.toString
      )(FirebaseJournal.reprToJson(repr))
        .map(_ => Success())

    Future.traverse(messages) {
      message =>
        val future =
          Future.traverse(message.payload)(writeRepr(_, message.persistenceId))
        future.onFailure {
          case _ =>
            import scala.concurrent.duration._
            Await.result(
              Future.traverse(message.payload.map(_.sequenceNr.toString))(
                FirebaseDatabase().delete(persistenceKeyPath, message.persistenceId, "events", _)
              ),
              3.seconds
            )
        }
        future.map(_ => Success())
    }
  }

  /**
    * Deletes the relevant messages in the `{persistenceKeyPath}/{persistenceId}/events/` path
    */
  def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    import scala.concurrent.duration._
    val messages =
      Await.result(
        FirebaseDatabase().getCollection(persistenceKeyPath, persistenceId, "events"),
        10.seconds
      )
    Future.traverse(
      messages.zipWithIndex
        .collect {
          case (_: JsObject, idx) if idx <= toSequenceNr =>
            idx
        }
    )(idx =>
      FirebaseDatabase()
        .merge(persistenceKeyPath, persistenceId, "events", idx.toString, "d")(
          JsBoolean(true)
        )
    )
      .map(_ => ())
  }

  /**
    * Reads the messages between the given sequence numbers in the `{persistenceKeyPath}/{persistenceId}/events/` path,
    * calling the callback for each message.
    */
  def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)
                         (recoveryCallback: (PersistentRepr) => Unit): Future[Unit] = {
    var replayedCount = 0L
    import com.seancheatham.storage.firebase.FirebaseDatabase.JsHelper

    import scala.concurrent.duration._
    val messages =
      Await.result(
        FirebaseDatabase().getCollection(persistenceKeyPath, persistenceId, "events"),
        10.seconds
      )
    messages
      .zipWithIndex
      .foreach {
        case (v: JsObject, i)
          if i >= fromSequenceNr &&
            i <= toSequenceNr &&
            replayedCount < max =>
          v.toOption
            .map(FirebaseJournal.jsonToRepr(_, i, persistenceId))
            .foreach {
              replayedCount += 1
              recoveryCallback(_)
            }
        case _ =>
      }
    Future.successful()
  }

  /**
    * Retrieves the latest event and its sequence number in `{persistenceKeyPath}/{persistenceId}/events/`
    */
  def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    val promise =
      Promise[Long]()

    import FirebaseDatabase.KeyHelper

    FirebaseDatabase().database.getReference(
      Seq(persistenceKeyPath, persistenceId, "events").keyify
    ).limitToLast(1)
      .addListenerForSingleValueEvent(
        new ValueEventListener {
          def onDataChange(dataSnapshot: DataSnapshot): Unit = {
            val childrenIterator = dataSnapshot.getChildren.iterator()
            val highestId =
              if (childrenIterator.hasNext)
                childrenIterator.next().getKey.toLong
              else
                0l
            promise success highestId
          }

          def onCancelled(databaseError: DatabaseError): Unit =
            promise failure new IllegalStateException(databaseError.getMessage)
        }
      )
    promise.future
  }
}

object FirebaseJournal {

  /**
    * Converts an "Any" event to a JsObject
    *
    * @param event         The event to serialize
    * @param serialization An implicit serialization from the actor system
    * @return a JsObject with:
    *         {
    *         "t" -> "int" | "long" | "float" | "double" | "boolean" | "string" | Serialization ID,
    *         "v" -> The Serialized Value
    *         }
    */
  def eventToJson(event: Any)(implicit serialization: Serialization): JsObject = {
    val (typeName, json) =
      event match {
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
    Json.obj("t" -> typeName, "v" -> json)
  }

  def reprToJson(repr: PersistentRepr)(implicit system: ExtendedActorSystem,
                                       serialization: Serialization): JsObject = {
    val payload =
      eventToJson(repr.payload)
    val manifest =
      repr.manifest
    val deleted =
      if (repr.deleted) Some(true) else None
    val sender =
      Option(repr.sender).map(Serialization.serializedActorPath)
    val writerUuid =
      repr.writerUuid
    payload ++
      Json.obj(
        "m" -> manifest,
        "w" -> writerUuid
      ) ++
      JsObject(
        Map[String, JsValue](
          deleted.toSeq.map(v => "deleted" -> Json.toJson(v)) ++
            sender.map(v => "s" -> Json.toJson(v)): _*
        )
      )
  }

  /**
    * Reads a Json value into an "Any"
    *
    * @param v a JsObject with:
    *          {
    *          "t" -> "int" | "long" | "float" | "double" | "boolean" | "string" | Serialization ID,
    *          "v" -> The Serialized Value
    *          }
    * @return The proper deserialized value
    */
  def jsonToEvent(v: JsValue)(implicit serialization: Serialization): Any = {
    val (typeName, json) =
      ((v \ "t").as[String], (v \ "v").get)
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

  /**
    * Converts the given JSON representation of a Repr into a Repr
    */
  def jsonToRepr(v: JsValue,
                 id: Long,
                 persistenceId: String)(implicit system: ExtendedActorSystem,
                                        serialization: Serialization): PersistentRepr =
    PersistentRepr(
      jsonToEvent(v),
      id,
      persistenceId,
      (v \ "m").as[String],
      (v \ "d").asOpt[Boolean].getOrElse(false),
      (v \ "s").asOpt[String].map(system.provider.resolveActorRef).orNull,
      (v \ "w").as[String]
    )

}