# Wormholes
See what's on the other side

## Makeshift Beta Access
You can sign up for beta access here https://goo.gl/forms/sXD4mGG6hETO5neB3

## Development Progress
- [x] Design a Mutex service to handle portal links across servers, worlds, or locations
- [x] Use links between two portals to send information to and from portals
- [x] Accurately project blocks from the destination portal to the source portal, applying the correct permutations depending on rotation
- [x] Correctly manage entiities in the destination portal and map them to the source portal for aperture projection
- [x] Make Use of protocollib to send packets to individual clients about remote entities (through) the portal
- [x] Use a Viewport system to determine if a player should see a projected object through the portal, or not depending on their orientation and position.
- [x] Do not continue to project chunks if the player target has not changed viewport positions
- [x] Correctly translate player velocities, entry/exit positions and look directions when teleporting
- [x] Use a fallback method for projecting players by substituting the entity with a villager
- [x] Design a provider system which mitigates control by players to the mutex service
- [x] Design a makeshift (rough around the edges) portal provider using internal wand mechanics
- [x] Design a world edit wand provider (for selecting portals with a wand instead of auto detection)
- [x] Do not hide entities INSIDE the frame
- [x] Capture fast moving entities (such as arrows)
- [x] Do not teleport entities unless they are inside the frame
- [x] Do not capture player entities for the viewing player
- [x] Portals should save and load automatically
- [x] Keep portal area chunks loaded at all times
- [x] A Simple command system for listing portals, going to them and more
- [x] Destroy projections and spawned entities when a portal is destroyed
- [x] Automagically detect if a player (with permission) has built a portal, register it, and broadcast it's registration to other servers if they exist
- [x] Use Relative move packets instead of only teleport packets
- [x] Create an event system for once
- [x] Simplistic Configuration system with an "advanced mode" via command to unlock more experimental options
- [x] Support Aperture SVC through bungeecord
- [x] Configurable option for max portals
- [x] Basic permission system for administrative operations
- [x] Polish messages, text etc. Use Command Tags.
- [x] Improve projection speed by ~10x by only scanning viewports
- [x] Improve packet utilization
- [x] Create a hud when right clicking portals for per portal settings
- [x] Support per portal settings
- [x] Do not send a player to the destination if a link cannot be established (causes a player to enter the server in the wrong position if a link is missing)
- [ ] Safley spawn actual player entities via protocollib to display players on the other side of the portal
- [ ] Use permissions with perhaps different ways of creating portals
- [ ] Use a name tag to indicate the player's name, and some symbol to represent the fact that they arent on this server and simply on the destination portal
- [ ] Display player abilities across portals (crouching, punching, item in hand, armor, and sprinting)
- [ ] Correctly show relative look packets (inaccurate right now)
- [ ] Support Sounds across portals
- [ ] Support particles across portals
- [ ] Adapt vertical portals when players exit them


## Version Support
| Version | Status    |
|---------|-----------|
| 1.7.10  | ~~Not Supported~~   |
| 1.8.X   | **Supported**   |
| 1.9.X   | **Supported** |
| 1.10.X  | **Supported**   |
| 1.11.X  | **Supported**   |
| 1.12.X  | **Supported**   |

## Plugin Compatability
| Plugin      | Interaction                                |
|-------------|--------------------------------------------|
| ProtocolLib | **Required**                                   |
| WorldEdit   | Planned (Selection)                        |
| WorldGuard  | Planned (Protection)                       |
| Vault       | Planned (Economy & Per Portal Permissions) |
| ViaVersion  | ~~Untested~~                                   |

## Tests Required for Release
| Module         | Local         | Bungeecord   |
|----------------|---------------|--------------|
| Projection SVC | **Complete** | **Complete** |
| Aperture SVC   | **Complete** | **Complete** |
| Wormhole SVC   | **Complete** | **Complete** |
| Transport SVC  | **Complete**  | **Complete** |
| Discovery SVC  | **Complete** | **Complete** |
| Oriented SVC   | **Complete** | **Complete** |

## Development Phase
* **Structure Core Functionality**
  * **Design Networking Backend for Local and Mutex connections**
    * **Design Wormhole connectons as effective proxy sockets**
    * **Design a layered stream system for prioritizing communication**
    * **Design a teleport handshake protocol for ensuring players are transported**
    * **Handle sending very large sets of data comprised of small stream packets**
  * **Design Coretick Threads for offloading work to other threads**
  * **Design Portal structure**
    * **Design PortalIdentity for directional and keyed identities**
    * **Design PortalPosition for local position and volume properties**
    * **Design LocalPortal for local usage such as fx, positioning and more**
    * **Design RemotePortal for remote reference usage containing only the PortalIdentity**
  * **Design a Service manager**
    * **Design a Provider service for creating and managing portals**
    * **Design a Mutex Service for managing communication between portals**
    * **Design a Projection Service for managing block projections**
    * **Design an Aperture Service for managing entity projections**
    * **Design an Entity Service for managing the visibility of entities inside projections**
  * **Create utilities for grasping portal surroundings, area, and relative information**
    * **Vector Math, Rotation, Permutations and Frustrum raycasts**
    * **Portal Positioning, Greyboxing areas and throw direction**
* **Optimization Pass**
  * **Improve efficiency of Viewport checking**
    * **Use Prebuilt cuboid frustrums instead of rebuilding it**
    * **Use build-deny checks for "before portal" checks instead of raycasting the player**
    * **Cache viewports instead of recreating them as needed**
  * **Improve Projection Permutation Time**
    * **Reduce sample data for blocks (ignore blocks surrounded by all solid blocks)**
    * **Reduce permutation scope (only run permutations on the viewport, not the entire sample map)**
