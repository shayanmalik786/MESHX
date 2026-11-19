# Android app UI/UX design

## v0.2 (current app)

These pictures are drawn by the app's own code with the demo mesh, so they always match the app.

| Screen | What it is for | Preview |
|---|---|---|
| Welcome | name of the user, one line about the idea | ![](screens/01-welcome.png) |
| Connect | four ways to connect: node over Bluetooth, node over Wi-Fi, phone to phone, demo mesh | ![](screens/02-connect.png) |
| Chats | conversations, unread count, channel for everyone | ![](screens/03-chats.png) |
| Chat | messages with status (sending, sent, delivered with hops and time, failed with retry) | ![](screens/04-chat.png) |
| Contacts | saved contacts, the list of heard nodes, QR code import | ![](screens/05-contacts.png) |
| Nodes | all devices with live status, signal, battery, distance | ![](screens/06-nodes.png) |
| Node details | signal, link score, route, counters, ping, position | ![](screens/07-node-detail.png) |
| Network: topology | live graph of nodes and links, routing mode switch, route in use | ![](screens/08-topology.png) |
| Network: map | positions as distance and direction from the user | ![](screens/09-map.png) |
| Network: traffic | packet counters, packets per second, live packet log | ![](screens/10-traffic.png) |
| Settings | profile, theme, location sharing, connection | ![](screens/11-settings.png) |
| Node settings | Wi-Fi name and password, radio, network key, firmware update, restart | ![](screens/12-node-settings.png) |
| Edit a setting | example: Wi-Fi password | ![](screens/13-edit-password.png) |
| My code | QR code of the user for other phones | ![](screens/14-my-code.png) |
| Dark theme | Nodes and topology | ![](screens/15-nodes-dark.png) ![](screens/16-topology-dark.png) |

Design decisions:

1. Signal information is always visible (hops, signal bars, delivery status), because proving multi-hop delivery is the point of the project.
2. The routing mode switch sits on the Network tab, so adaptive, shortest-hop and flooding can be compared live.
3. The demo mesh has the four team nodes and can switch nodes off, so route recovery can be shown without hardware.
4. Large touch targets (48 dp), high contrast and a dark theme for outdoor use.
5. Colours: teal `#0F9D8A` for the mesh, see `ui/theme/Theme.kt`. Icons: [Lucide](https://lucide.dev) (ISC licence).

## v0.1 (first design in Figma)

- Figma design file (7 screens, components, style guide): https://www.figma.com/design/AcwXg0kupgabqYOPDhirem
- FigJam user flow and system schematic: https://www.figma.com/board/8ZKwC6RBukY7y6MXb6kNz0
- Exported pictures: [`figma-v0.1/`](figma-v0.1)
