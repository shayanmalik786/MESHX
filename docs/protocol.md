# MeshX protocol v2

There are two layers.

| Layer | Between | Carried by | Who must agree |
|---|---|---|---|
| Line protocol | phone app and its node | Bluetooth LE or Wi-Fi (TCP) | app and firmware. This is the contract. |
| Mesh packets | node and node | LoRa | all nodes |

The app contains a complete node written in Kotlin: `android-app/app/src/main/java/com/meshx/app/mesh/SoftNode.kt`.
It speaks exactly this protocol and is the reference for the firmware. The encoders and parsers for the lines
are in `protocol/Protocol.kt`, and `ProtocolTest.kt` shows every line with an example.

## 1. Line protocol (phone and node)

### Transport

| | Bluetooth LE | Wi-Fi |
|---|---|---|
| How | Nordic UART Service | node is an access point with a TCP server |
| Address | device name starts with `MeshX` | `192.168.4.1`, port `4403` |
| Phone to node | write to `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | socket |
| Node to phone | notify on `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | socket |

Service UUID: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`. The app asks for MTU 247 and cuts long lines into
pieces, so the node must collect bytes until it sees the end of a line.

A line is UTF-8 text. Fields are separated by `|`. A line ends with `\n` (a `\r` is ignored) and is at most
1024 bytes. Free text is always the last field, so it may contain `|`. Numbers are decimal.

### Start of a connection

1. The phone sends `HELLO|2|<name>`. The node takes this name as its name on the mesh. Lines before `HELLO` are ignored.
2. The node sends `ME`, then everything it knows (`STAT`, `NODE`, `ROUTE`, `POS`, `LINK`), then `READY`.
   The app waits 8 seconds for `READY`.
3. The app sends `GET|*`. The node answers with one `CFG` line for every setting it supports, then `OK|GET`.
   The app only shows settings the node reported, so a simple node can leave some out.
4. After that the node sends a line again only when its content changed (checked about once per second),
   and events (`SENT`, `ACK`, `FAIL`, `RX`, `PKT`, `PONG`) at once.

### Phone to node

| Line | Meaning |
|---|---|
| `HELLO|2|<name>` | start, protocol version 2, name of the user (at most 20 characters) |
| `MSG|<msgId>|<to>|<text>` | send a message. `to` 0 means everyone. Text is at most 160 bytes of UTF-8. |
| `SET|<key>|<value>` | change a setting, answered by `CFG|<key>|<value>` and `OK|<key>`, or by `ERR|<key>|<reason>` |
| `GET|<key>` or `GET|*` | read one setting or all of them |
| `POS|<lat>|<lon>` | position of the phone, the node shares it with the mesh |
| `PING|<id>|<dst>` | measure the round trip to a node, answered by `PONG` |
| `OTA|BEGIN|<size>|<crc32 hex>|<label>` | start a firmware update |
| `OTA|DATA|<seq>|<base64>` | one piece of the file, `seq` starts at 0 |
| `OTA|END` / `OTA|ABORT` | finish or cancel the update |
| `REBOOT` | restart the node |

### Node to phone

| Line | Meaning |
|---|---|
| `ME|<id>|<battery>|<fw>|<name>` | the connected node itself |
| `READY` | the first tables are complete |
| `SENT|<msgId>` | the message left the node |
| `ACK|<msgId>|<hops>|<rttMs>` | the receiver confirmed the message |
| `FAIL|<msgId>|<reason>` | no confirmation after all tries (`timeout`), or `bad address`, `empty` |
| `RX|<msgId>|<from>|<to>|<hops>|<rssi>|<snr>|<text>` | a message arrived. `to` 0 means it was sent to everyone. |
| `NODE|<id>|<rssi>|<snr>|<battery>|<loss%>|<score>|<hops>|<nextHop>|<lastSeenSec>|<name>` | one other node. `lastSeenSec` 0 = online. `hops` 0 = no route. `rssi` 0 = not a direct neighbour. `score` = weakest link on the route. |
| `LINK|<a>|<b>|<score>` | quality of the link between two nodes, 0 = the link is gone |
| `ROUTE|<dst>|<id,id,...>` | the route in use, from this node to `dst`, both ends included. `*` = flooding, empty = no route. |
| `POS|<id>|<lat>|<lon>|<ageSec>` | last known position of a node |
| `STAT|<id>|<uptimeSec>|<tx>|<rx>|<fwd>|<drop>|<phone 0/1>|<fw>` | counters of a node, and whether a phone is connected to it |
| `PKT|<kind>|<src>|<dst>|<via>|<hops>|<rssi>|<size>` | one packet seen on the radio. `kind` is MSG, ACK, BEACON, PING or PONG. `via` equal to my id means my node sent it. |
| `CFG|<key>|<value>` | value of a setting |
| `OK|<what>` / `ERR|<what>|<reason>` | result of `SET`, `GET`, `REBOOT`. `ERR|FRAME|...` = line not understood. |
| `PONG|<id>|<rttMs>|<hops>` | answer to `PING`, `rttMs` -1 = no answer |
| `OTA|READY|<chunk>` | the node accepts the update, with this many bytes per `OTA|DATA` line |
| `OTA|ACK|<seq>` | piece received, send the next one |
| `OTA|DONE` / `OTA|ERR|<reason>` | the file is complete and its CRC32 is correct, or the update failed |

Example: sending "Hi" from node 1 to node 4 and getting the answer.

```
phone: MSG|17|4|Hi
node:  SENT|17
node:  PKT|MSG|1|4|1|0|0|31
node:  ACK|17|3|2140
node:  RX|5021|4|1|3|-84|7.0|Got your message, three hops away!
```

### Settings

| Key | Values | Note |
|---|---|---|
| `id` | 1 to 65534 | node number, must be different on every node |
| `route` | `adaptive`, `shortest`, `flood` | routing mode |
| `sf` | 7 to 12 | LoRa spreading factor |
| `bw` | 125, 250, 500 | LoRa bandwidth in kHz |
| `power` | 2 to 20 | transmit power in dBm |
| `wifi` | `on`, `off` | Wi-Fi access point of the node |
| `wifi_ssid` | 1 to 32 bytes, no `|` | name of the access point |
| `wifi_pass` | empty, or 8 to 63 characters | empty means an open network |
| `net_key` | 8 to 32 characters, no `|` or `&` | key of the network, the same on all nodes |
| `freq`, `fw`, `hw`, `ota` | read only | frequency in MHz, firmware version, board, `yes`/`no` for updates from the phone |

The rules are in `ConfigKeys.problem()`, used by the app before sending and by the node before accepting.
All nodes of one mesh need the same `sf`, `bw` and `net_key`.

### QR codes

| Code | Content |
|---|---|
| contact | `meshx://contact?id=2&name=Fatima` |
| network key | `meshx://net?key=<key>` |

## 2. Mesh packets (node and node)

Every packet carries these fields. The reference node sends them as readable text,
`kind|src|dst|id|attempt|ttl|hops|via|next|mode|body`. The firmware should pack the same fields into a few
bytes to save air time. Phones never see this format, so it can change without touching the app.

| Field | Meaning |
|---|---|
| `kind` | `B` beacon, `M` message, `A` acknowledgement, `P` ping, `Q` pong |
| `src`, `dst` | first sender and final receiver, `dst` 0 = everyone |
| `id` | number of the message, chosen by the sender |
| `attempt` | 1, 2, 3 |
| `ttl` | hops left, starts at 5 |
| `hops` | hops made so far |
| `via` | the node that transmitted this copy |
| `next` | the node that should handle it, 0 = everyone who hears it (flooding) |
| `mode` | `a`, `s` or `f`, the routing mode chosen by the sender |
| `body` | text of a message, or the beacon data |

Rules:

- **Beacon.** Every node broadcasts a beacon on a fixed period (5 s by default, 3 s in the demo). Body:
  `battery;uptime;tx;rx;fwd;drop;phone;fw;lat;lon;neighbour:score,...;name`. Nodes repeat beacons of others
  once, so everyone learns all nodes and all links (link state).
- **Neighbours.** From every packet heard directly a node updates the RSSI and SNR of that neighbour as a
  moving average. A missed beacon counts as a lost packet. A neighbour that is silent for several periods is dropped.
- **Link score**, 0 to 100: `35% RSSI (-120..-50 dBm) + 25% SNR (-10..+10 dB) + 25% delivery + 15% battery`.
  See `mesh/LinkScore.kt`.
- **Routing.** `adaptive`: the path with the lowest sum of `100 / score`, then fewest hops.
  `shortest`: fewest hops, then lowest cost. `flood`: no route, everyone repeats.
- **Forwarding.** A packet with `next` not 0 is handled only by that node, also when others overhear it.
  Each hop works out the next hop again with its own table. `ttl` goes down and `hops` goes up on every hop.
- **Flooding.** A node repeats a flooded packet once after a short random wait and remembers `(src, id)` to
  drop copies.
- **Reliability.** The receiver answers a message with an `A` packet. Without it the sender tries again, up
  to 3 times. The last try is flooded, so a broken route is no dead end. Messages to everyone are not acknowledged.
- **Duplicates.** The receiver remembers `(src, id)` and delivers a message to its phone only once.

## 3. Changes from v0.1

`HELLO` carries a version and the name. New: settings (`SET`, `GET`, `CFG`), `NODE`/`LINK`/`ROUTE`/`STAT`/`POS`
tables, the `PKT` traffic log, `PING`, firmware update, Wi-Fi transport. Node numbers go up to 65534 and
`0` is the address for everyone (it was 255).
