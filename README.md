A journal plugin for Akka Persistence, backed by Firebase

[![Build Status](https://travis-ci.org/SeanCheatham/akka-persistence-firebase.svg?branch=master)](https://travis-ci.org/SeanCheatham/akka-persistence-firebase)

# Overview

Use this library/plugin in your Akka architecture to provide persistence backed by Firebase.  If your system already leverages
Firebase in your stack, then using it as a journal/snapshot mechanism is quite convenient.

Under the hood, this library uses my other [scala-storage](https://github.com/SeanCheatham/scala-storage) library to interface
with Firebase.  As such, the real meat and potatoes of this Akka Journal exists over there; this is merely a simple wrapper for it.

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
    firebase {
        url = "https://url-of-your-app.firebaseio.com/"
        service_account_key_location = "/path/to/service/account/key.json"
    }
}

firebase-snapshot-store {
    class = "com.seancheatham.akka.persistence.FirebaseSnapshotStore"
    firebase {
        url = "https://url-of-your-app.firebaseio.com/"
        service_account_key_location = "/path/to/service/account/key.json"
    }
    // Optional - base path within your Firebase Database to use as the journal storage
    base_key_path = "infrastructure/akka/persistence"
}
```