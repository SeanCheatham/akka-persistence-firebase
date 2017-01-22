package com.seancheatham.akka.persistence

import akka.persistence.CapabilityFlag
import akka.persistence.journal.JournalSpec
import com.seancheatham.storage.firebase.FirebaseDatabase
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class FirebaseJournalSpec extends JournalSpec(ConfigFactory.load()) {

  protected def supportsRejectingNonSerializableObjects: CapabilityFlag =
    false

  private val persistenceKeyPath =
    config.getString("firebase-journal.base_key_path")

  override def beforeAll(): Unit = {
    super.beforeAll()
    clearPersistenceData()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    clearPersistenceData()
  }

  import scala.concurrent.ExecutionContext.Implicits.global
  private def clearPersistenceData() =
    Await.result(FirebaseDatabase().delete(persistenceKeyPath), Duration.Inf)

}