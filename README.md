A journal plugin for Akka Persistence, backed by Firebase

[![Build Status](https://travis-ci.org/SeanCheatham/akka-persistence-firebase.svg?branch=master)](https://travis-ci.org/SeanCheatham/akka-persistence-firebase)

# Usage
This library is written in Scala.  It _might_ interoperate with other JVM languages, but I make no guarantees.

This library uses Typesafe's Play JSON library for serialization of content.  I hope to support other mechanisms at some point.

## Include the library in your project.
### TODO

## Update your application.conf``
```
akka {
    persistence {
        journal.plugin = "firebase-journal"
        snapshot-store.plugin = "firebase-snapshot-store"
        }
    }
}
firebase-journal {
    class = "com.seancheatham.akka.persistence.FirebaseJournal"
    // Optional - base path within your Firebase Database to use as the journal storage
    base_key_path = "infrastructure/akka/persistence"
}
firebase-snapshot-store {
    class = "com.seancheatham.akka.persistence.FirebaseSnapshotStore"
    base_key_path = "infrastructure/akka/persistence"
}
firebase {
    url = "https://url-of-your-app.firebaseio.com/"
    service_account_key_location = "/path/to/service/account/key.json"
}
```