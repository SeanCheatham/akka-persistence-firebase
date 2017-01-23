package fixtures

import com.seancheatham.storage.firebase.FirebaseDatabase
import com.typesafe.config.{Config, ConfigFactory}

object FirebaseConfig {

  lazy val config: Config =
    ConfigFactory.parseString(
      """akka.persistence {
        |  journal.plugin = "firebase-journal"
        |  snapshot-store.plugin = "firebase-snapshot-store"
        |}
        |
        |firebase-journal {
        |  class = "com.seancheatham.akka.persistence.FirebaseJournal"
        |  base_key_path = "akka-firebase-persistance-testing/persistence/journal"
        |}
        |
        |firebase-snapshot-store {
        |  class = "com.seancheatham.akka.persistence.FirebaseSnapshotStore"
        |  base_key_path = "akka-firebase-persistance-testing/persistence/snapshot"
        |}
      """.stripMargin
    ).withFallback(ConfigFactory.load())

}
