`GraffitiCore` implements P2P networking protocols for GraffitiPC and GraffitiAndroid, the REST/HTTP API layer, and houses the cross-platform Web application assets.

* **Web UI & Build Tooling**:
  * Built with TypeScript (ES2022 output) via Gradle task `compileTypescript` (`npx tsc`).
  * Source located at [`src/main/resources/web`](file:///d:/IdeaProjects/GraffitiCore/src/main/resources/web).
* **Core Subsystems Provided**:
  * `r3.graffiti.GraffitiP2P`: Mesh peer discovery, ping/pong, challenge-response, content synchronization.
  * `r3.graffiti.GraffitiAPI`: HTTP/JSON REST API serving peer status, identity, messages, and storage.
  * `r3.graffiti.*`: Message types (`ChallengeMessage`, `ContentRequestMessage`, `EncryptedContentMessage`, etc.).
