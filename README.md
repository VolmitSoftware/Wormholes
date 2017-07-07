# What Are Wormholes?
Wormholes are portals with a twist. You can peer into a portal, and see the destination's blocks, and entities walking around. As you walk around the portal, the blocks through the portal update to the destination's blocks. 

Walking through a portal is smooth. Looking to the side and strafing through the portal will send you strafing out. Portals carry velocity and have the ability to transport all types of entities. That means you could look into a portal, see an enemy and fire an arrow through the portal and hit the enemy. 

Portals work cross world, cross location, and even cross server. Portals are capable of any size that is odd numbered, and the same width and height. For example, 3x3, 5x5, 7x7, 9x9 (or higher). Portals can also face any direction including vertical portals. Meaning you could have a vertical portal peering down on a pvp map. Any player looking through a portal would see a top down projection of that pvp map!

Have a series of pictures called a video. (click)

[![Video...](https://img.youtube.com/vi/pR23FDMmbr4/0.jpg)](https://www.youtube.com/watch?v=pR23FDMmbr4)

## Makeshift Beta Access
You can sign up for beta access here https://goo.gl/forms/sXD4mGG6hETO5neB3

## Development Progress
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
    * **Design an IO Service for managing portal data**
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
    * **Improve viewport check time by keeping prebuilt cuboids on hand**
    * ~~Split up viewports with partial renderers to create a more responsive renderer at the same speed~~
  * **Reduce Bandwidth usage of players**
    * **Chunk send up to 6 chunks every 2 ticks instead of all at once**
    * **Reduce over-sending of modifications behind the queue (oversending useless packets)**
  * ~~Reduce Clientside hitches due to assumed lighting updates~~
    * ~~Split up packets containing block changes that would result in a lighting check clientside~~
* **Front End design**
  * **Design a fast and simple method of creating portals**
    * ~~Support world edit selection with a portal command~~
    * ~~Make use of world edit to "auto generate" portals~~
    * **Support a method by throwing an ender pearl through the portal to "open the portal"**
    * **Support lighting it like a normal portal**
  * **Make use of MFA Sound scheduling (Multi Freq Audio)
    * **Play a sound when opening or destroying portals**
    * **Play portal sounds with some rnd across the entire portal mesh**
    * **Play break and teleport sounds depending on the event**
  * **Support VFX for portals using PortalPosition for visual appeal**
    * **Create "ripples" across the portal when something enters or exits the mesh, or breaks the frame blocks**
    * **Create a faint layer of noise to portray the feeling of an actual portal mesh**
    * **Create lighting effects because they are really cool when done right (arcs, splits and sfx for it)**
  * **Design a HUD system when right clicking the portal... Inventory GUIs are a joke**
    * **Make use of moving armor stand name tags to allow selection of multiple items**
    * **Use the player's scroll wheel (hotbar selection) to scroll through the hud list of items**
    * **Utilize left and right click as action intents**
    * **Auto close when the player moves position, but allow looking around freely**
    * **Add the ability to reverse portal polarities**
  * **Design a simple debugging system for monitoring portal performance**
* **Polishing**
  * ~~Better user feedback such as action bars, titles, chat messages etc~~
    * ~~Use chat tags. Everybody is doing it.~~
    * ~~Add hr breaks for chat, it looks good.~~
    * ~~Add hover and action clicks for command messages~~
    * ~~Better sound effects please~~
  * ~~Add additional configuration options~~
  * ~~Some sort of splash screen?~~
  * ~~Support WorldEdit, WorldGuard, and others.~~
* **Networking**
  * **Quickly send players through portals correctly**
  
## Version Support
| Version | Status    |
|---------|-----------|
| 1.7.10  | ~~Not Supported~~   |
| 1.8.X   | **Needs Testing**   |
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

## Support
| Feature                 | Status    |
|-------------------------|-----------|
| ViaVersion              | Supported |
| Bungeecord (1.8+ combo) | Supported |
| 1.8+ Spigot             | Supported |

## Rolling Changelog 1.1p
* Use Chunk Map packets for projections instead of block change packets to prevent lighting issues for clients (lag)
* Add a portal wand for designing portal frames (quickly)
* Add additional feedback to error messages via title message
* Add feedback for giving a portal a name
