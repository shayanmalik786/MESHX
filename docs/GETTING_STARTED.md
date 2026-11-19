# Getting started

## 1. Run the app (10 minutes, no hardware)

1. Install Android Studio. Open the `android-app` folder (not the whole repository).
2. Wait for the Gradle sync, connect a phone (Settings → About phone → tap Build number 7 times →
   Developer options → USB debugging ON) and press Run.
3. In the app: type your name → **Demo mesh**. You are node 1, with Fatima (2), Umaiza (3) and Hamna (4).
4. Try this:
   - Chats: write to Hamna. The message goes 1 → 2 → 3 → 4 and gets two ticks with "3 hops".
   - Network: tap Hamna on the graph, then switch between Adaptive, Shortest and Flood and watch the route change.
   - Nodes → Fatima → switch "Demo: node power" off. The route to Hamna moves to the weak link 1 → 3 → 4 and messages still arrive. Switch it on again.
   - Settings → Node settings: change the Wi-Fi password, run a firmware update.

## 2. Phone to phone (two or three phones, no hardware)

Install the app on every phone. On each one: Connect → **Phone to phone**, type a different node number on
each phone, press Start and allow the permissions. After some seconds the phones see each other on the Nodes tab.

## 3. Learn the code

| Day | File | What you learn |
|---|---|---|
| 1 | `model/Models.kt`, `protocol/Protocol.kt`, `docs/protocol.md` | what the phone and the node say to each other |
| 2 | `mesh/LinkScore.kt`, `mesh/SoftNode.kt` | beacons, link score, routing, forwarding, retries |
| 3 | `mesh/SimMesh.kt`, the tests in `app/src/test` | the demo mesh, how the logic is tested |
| 4 | `core/UiState.kt`, `core/AppController.kt` | app logic and screen state |
| 5 | `ui/screens/ChatScreens.kt`, `ui/components/Components.kt` | how Compose screens are built |
| 6 | `link/BleLink.kt`, `platform/AndroidDevice.kt` | Bluetooth and permissions |

## 4. Working with Git

- One branch per task (for example `app/chat-screen`), small commits with clear messages.
- Open a pull request and let a teammate look at it before merging.
- Never commit passwords or keys.

## 5. What comes next

1. Node firmware that speaks `docs/protocol.md` (see `firmware/README.md`).
2. Connect the app to the real nodes over Bluetooth, then test on four nodes.
3. Field tests and the comparison of the three routing modes.
