package com.seancheatham.akka.persistence

import java.util.concurrent.Executors

import akka.persistence.snapshot.SnapshotStore
import akka.persistence.{SelectedSnapshot, SnapshotMetadata, SnapshotSelectionCriteria}
import akka.serialization.{Serialization, SerializationExtension}
import com.seancheatham.akka.persistence.FirebaseSnapshotStore.SnapshotItem
import com.seancheatham.storage.firebase.FirebaseDatabase
import com.typesafe.config.Config
import play.api.libs.json.{Format, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

/**
  * An Akka Persistence Snapshot Storage system which is backed by a Google Firebase instance.  Firebase provides several
  * features, and among them is a Realtime Database.  Although databases aren't exactly conducive to storing binary
  * snapshot data, the convenience of doing it in Firebase may outweigh it for some people.
  *
  * The configuration settings defined for this plugin are:
  * {
  * base_key_path = "infrastructure/akka/persistence"
  * }
  */
class FirebaseSnapshotStore(config: Config) extends SnapshotStore {

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
    * will be written to `{persistenceKeyPath}/X/snapshots`
    */
  val persistenceKeyPath: String =
    config.getString("base_key_path")

  /**
    * The ActorSystem to which this journal's Actor belongs
    */
  private implicit val actorSystem =
    context.system

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

  def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] =
    db.lift(persistenceKeyPath, persistenceId, "snapshots")
      .map(_.fold[Option[SelectedSnapshot]](None) {
        snapshots =>
          // TODO: This is really ugly, because it will grab ALL snapshots from the database, and then filter them
          // on the application-side.  If possible, find a way to retrieve just the child KEYS for the snapshots
          val validSnapshots =
            snapshots.as[Map[String, SnapshotItem]]
              .filter {
                case (_, item) =>
                  item.n <= criteria.maxSequenceNr &&
                    item.n >= criteria.minSequenceNr &&
                    item.t <= criteria.maxTimestamp &&
                    item.t >= criteria.minTimestamp
              }
          if (validSnapshots.nonEmpty) {
            val latest =
              validSnapshots.maxBy(_._1)._2
            Some(
              SelectedSnapshot(
                latest.metadata(persistenceId),
                latest.value
              )
            )
          } else {
            None
          }
      })

  def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
    val persistenceId =
      metadata.persistenceId
    val item =
      Json.obj(
        "n" -> metadata.sequenceNr,
        "t" -> metadata.timestamp
      ) ++ anyToJson(snapshot)
    val key =
      metadata.sequenceNr + ":" + metadata.timestamp
    db.write(persistenceKeyPath, persistenceId, "snapshots", key)(item)
      .map(_ => ())
  }

  def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = {
    val persistenceId =
      metadata.persistenceId
    if (metadata.timestamp > 0) {
      val key =
        metadata.sequenceNr + ":" + metadata.timestamp
      db.delete(persistenceKeyPath, persistenceId, "snapshots", key)
        .map(_ => ())
    } else {
      // TODO: This is really ugly, because it will grab ALL snapshots from the database, and then filter them
      // on the application-side.  If possible, find a way to retrieve just the child KEYS for the snapshots
      db.lift(persistenceKeyPath, persistenceId, "snapshots")
        .map(_.fold(Future.successful())(snapshots =>
          Future.traverse(
            snapshots.as[Map[String, SnapshotItem]]
              .keysIterator
              .filter(_.startsWith(metadata.sequenceNr.toString))
          )(db.delete(persistenceKeyPath, persistenceId, "snapshots", _))
            .map(_ => ())
        )
        )
    }
  }
  def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] =
    db.lift(persistenceKeyPath, persistenceId, "snapshots")
      .flatMap(
        _.fold(Future.successful())(snapshots =>
          Future.traverse(
            // TODO: This is really ugly, because it will grab ALL snapshots from the database, and then filter them
            // on the application-side.  If possible, find a way to retrieve just the child KEYS for the snapshots
            snapshots.as[Map[String, SnapshotItem]]
              .iterator
              .collect {
                case (id, item)
                  if item.n <= criteria.maxSequenceNr &&
                    item.n >= criteria.minSequenceNr &&
                    item.t <= criteria.maxTimestamp &&
                    item.t >= criteria.minTimestamp =>
                  id
              }
          )(db.delete(persistenceKeyPath, persistenceId, "snapshots", _))
            .map(_ => ())
        )
      )
}

object FirebaseSnapshotStore {

  case class SnapshotItem(n: Long, t: Long, v: JsValue, vt: String) {
    def value(implicit serialization: Serialization): Any =
      serializedToAny(vt, v)

    def metadata(persistenceId: String) =
      SnapshotMetadata(persistenceId, n, t)
  }

  object SnapshotItem {
    def apply(sequenceNr: Long,
              timestamp: Long,
              value: Any)(implicit serialization: Serialization): SnapshotItem = {
      val (vt, v) =
        anyToSerialized(value)
      SnapshotItem(sequenceNr, timestamp, v, vt)
    }
  }

  implicit val format: Format[SnapshotItem] =
    Json.format[SnapshotItem]

}