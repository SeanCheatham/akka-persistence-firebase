package com.seancheatham.akka.persistence

import akka.persistence.CapabilityFlag
import akka.persistence.snapshot.SnapshotStoreSpec
import com.seancheatham.storage.firebase.FirebaseDatabase
import fixtures.FirebaseConfig

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class FirebaseSnapshotStoreSpec extends SnapshotStoreSpec(FirebaseConfig.config) {

  protected def supportsRejectingNonSerializableObjects: CapabilityFlag =
    false

  private val persistenceKeyPath =
    FirebaseConfig.config.getString("firebase-snapshot-store.base_key_path")

  override def beforeAll(): Unit = {
    super.beforeAll()
    clearPersistenceData()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    clearPersistenceData()
  }

  import scala.concurrent.ExecutionContext.Implicits.global

  private val db =
    FirebaseDatabase.fromConfig(config.getObject("firebase-snapshot-store").toConfig)

  private def clearPersistenceData() =
    Await.result(db.delete(persistenceKeyPath), Duration.Inf)

}