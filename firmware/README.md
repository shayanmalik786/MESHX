# Node firmware

Status: the two-node LoRa test sketches work on the breadboard (ESP32-S3 + Ra-02). The next step is the
firmware that speaks [protocol v2](../docs/protocol.md), so the app can talk to the real nodes.

The app already contains a complete node in Kotlin, `android-app/app/src/main/java/com/meshx/app/mesh/SoftNode.kt`
(about 800 lines, with tests in `MeshTest.kt`). The firmware is a port of that file to C++. Keep the two side by side.

Planned structure (PlatformIO, Arduino framework, RadioLib + NimBLE):

```
firmware/
  platformio.ini
  src/
    main.cpp          setup/loop, wires everything together
    radio.cpp/.h      LoRa (RadioLib) send/receive, RSSI/SNR
    phone_ble.cpp/.h  Nordic UART Service, line protocol
    phone_wifi.cpp/.h access point + TCP server on port 4403, same lines
    mesh.cpp/.h       packets, TTL, duplicate cache, forwarding, ACK and retry
    routing.cpp/.h    neighbour table, link score, adaptive / shortest / flood
    config.cpp/.h     settings in flash (Preferences): id, radio, Wi-Fi name and password, network key
    ota.cpp/.h        firmware update from the phone (Update library)
    crypto.cpp/.h     AES-GCM with the network key
```

Milestones:

1. `HELLO` / `ME` / `READY` over Bluetooth: the app connects and shows the node.
2. `MSG`, `RX`, `ACK` between two nodes: a chat between two phones.
3. Beacons, `NODE` / `LINK` tables: the Nodes and Network tabs fill with real data.
4. Forwarding and the three routing modes on four nodes.
5. Settings (`SET` / `GET`), Wi-Fi access point, firmware update.
