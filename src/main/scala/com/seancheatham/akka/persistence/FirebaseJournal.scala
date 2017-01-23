package com.seancheatham.akka.persistence

import java.util.concurrent.Executors

import akka.actor.{ActorLogging, ExtendedActorSystem}
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.{AtomicWrite, PersistentRepr}
import akka.serialization.{Serialization, SerializationExtension}
import com.google.firebase.database.{DataSnapshot, DatabaseError, ValueEventListener}
import com.seancheatham.akka.persistence.FirebaseJournal.EventItem
import com.seancheatham.storage.firebase.FirebaseDatabase
import com.typesafe.config.Config
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Success, Try}

/**
  * An Akka Persistence Journaling system which is backed by a Google Firebase instance.  Firebase provides several
  * features, and among them is a Realtime Database.  Their database is document-based, meaning it is effectively
  * modeled as a giant JSON object, thus allowing for an expressive way of interacting with it.  The true power, however,
  * comes from its "realtime"-ness.  Firebase is designed for high-performance reads and writes, as well as for listening
  * on real-time connections.  This makes it effective for Akka Journaling.
  *
  * The configuration settings defined for this plugin are:
  * {
  * base_key_path = "infrastructure/akka/persistence"
  * }
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
    * A Firebase DB instance
    */
  private val db =
    FirebaseDatabase.fromConfig(config)

  /**
    * Appends the given messages to the `{persistenceKeyPath}/{persistenceId}/events/{seqNr}` path
    */
  def asyncWriteMessages(messages: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] = {
    def writeRepr(repr: PersistentRepr,
                  persistenceId: String) =
      db.write(
        persistenceKeyPath,
        persistenceId,
        "events",
        repr.sequenceNr.toString
      )(Json.toJson(EventItem(repr)))
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
                db.delete(persistenceKeyPath, message.persistenceId, "events", _)
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
    db.getChildKeys(persistenceKeyPath, persistenceId, "events")
      .map(
        _.map(_.toLong)
          .filter(_ <= toSequenceNr)
          .map(_.toString)
          .map(idx => db.merge(persistenceKeyPath, persistenceId, "events", idx, "d") _)
      )
      .flatMap(
        Future.traverse(_)(_.apply(JsBoolean(true)))
          .map(_ => ())
      )
  }

  /**
    * Reads the messages between the given sequence numbers in the `{persistenceKeyPath}/{persistenceId}/events/` path,
    * calling the callback for each message.
    */
  def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)
                         (recoveryCallback: (PersistentRepr) => Unit): Future[Unit] = {
    var replayedCount = 0L
    import com.seancheatham.storage.firebase.FirebaseDatabase.JsHelper
    db.getCollection(persistenceKeyPath, persistenceId, "events")
      .map(
        _.zipWithIndex
          .collect {
            case (v: JsObject, i) if i >= fromSequenceNr && i <= toSequenceNr =>
              v -> i
          }
      )
      .map(messages =>
        while (messages.hasNext && replayedCount < max) {
          val (v, i) =
            messages.next()
          v.toOption
            .map(_.as[EventItem].repr(i, persistenceId))
            .foreach {
              replayedCount += 1
              recoveryCallback(_)
            }
        }
      )
  }

  /**
    * Retrieves the latest event and its sequence number in `{persistenceKeyPath}/{persistenceId}/events/`
    */
  def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    val promise =
      Promise[Long]()

    import FirebaseDatabase.KeyHelper

    db.database.getReference(
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
    * Events get stored using this structure, however the field names are shortened when exporting to JSON,
    * to save space and time.
    *
    * @param manifest   @see [[akka.persistence.PersistentRepr#manifest]]
    * @param deleted    @see [[akka.persistence.PersistentRepr#deleted]]
    * @param sender     @see [[akka.persistence.PersistentRepr#sender]]
    * @param writerUuid @see [[akka.persistence.PersistentRepr#writerUuid]]
    * @param value      The serialized value of the Repr's payload.  If the value is a primitive, it'll be stored as a Json
    *              primitive.  If the value is some sort of serializable object, the object gets serialized
    *                   and then Base64 Encoded.
    * @param valueType  A hint as to what the [[com.seancheatham.akka.persistence.FirebaseJournal.EventItem#value()]] is
    */
  case class EventItem(manifest: String,
                       deleted: Boolean,
                       sender: Option[String],
                       writerUuid: String,
                       value: JsValue,
                       valueType: String) {

    /**
      * Base64 decodes and deserializes this item's value
      *
      * @param serialization An Actor Serialization
      * @return an Any, per deserialization rules
      */
    def value(implicit serialization: Serialization): Any =
      serializedToAny(valueType, value)

    /**
      * Converts this EventItem into a Persistence Repr
      *
      * @param id            The [[akka.persistence.PersistentImpl#sequenceNr()]]
      * @param persistenceId The [[akka.persistence.PersistentImpl#persistenceId()]]
      * @param system        An implicit actor system, for deserializing
      * @param serialization The actor's serialization
      * @return a PersistenceRepr
      */
    def repr(id: Long,
             persistenceId: String)(implicit system: ExtendedActorSystem,
                                    serialization: Serialization): PersistentRepr =
      PersistentRepr(serializedToAny(valueType, value), id, persistenceId, manifest, deleted, sender.map(system.provider.resolveActorRef).orNull, writerUuid)

  }

  object EventItem {
    def apply(repr: PersistentRepr)(implicit system: ExtendedActorSystem,
                                    serialization: Serialization): EventItem = {
      val (vt, v) =
        anyToSerialized(repr.payload)
      EventItem(
        repr.manifest,
        repr.deleted,
        Option(repr.sender).map(Serialization.serializedActorPath),
        repr.writerUuid,
        v,
        vt
      )
    }
  }

  implicit val format: Format[EventItem] =
    Format[EventItem](
      Reads[EventItem](v =>
        JsSuccess(
          EventItem(
            (v \ "m").as[String],
            (v \ "d").asOpt[Boolean].getOrElse[Boolean](false),
            (v \ "s").asOpt[String],
            (v \ "w").as[String],
            (v \ "v").get,
            (v \ "vt").as[String]
          )
        )
      ),
      Writes[EventItem] {
        item =>
          val objItems =
            Map(
              "m" -> Json.toJson(item.manifest),
              "w" -> Json.toJson(item.writerUuid),
              "v" -> Json.toJson(item.value),
              "vt" -> Json.toJson(item.valueType)
            ) ++
              item.sender.map("s" -> JsString(_)) ++
              (if (item.deleted) Seq("d" -> JsBoolean(true)) else Seq.empty)
          JsObject(objItems)
      }
    )
  Json.format[EventItem]

}