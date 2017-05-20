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
- [ ] Do not send a player to the destination if a link cannot be established (causes a player to enter the server in the wrong position if a link is missing)
- [ ] Safley spawn actual player entities via protocollib to display players on the other side of the portal
- [x] Use a fallback method for projecting players by substituting the entity with a villager
- [ ] Use a name tag to indicate the player's name, and some symbol to represent the fact that they arent on this server and simply on the destination portal
- [ ] Display player abilities across portals (crouching, punching, item in hand, armor, and sprinting)
- [ ] Use Relative move packets instead of only teleport packets
- [ ] Correctly show relative look packets (inaccurate right now)
- [x] Design a provider system which mitigates control by players to the mutex service
- [x] Design a makeshift (rough around the edges) portal provider using internal wand mechanics
- [x] Design a world edit wand provider (for selecting portals with a wand instead of auto detection)
- [ ] Automagically detect if a player (with permission) has built a portal, register it, and broadcast it's registration to other servers if they exist
- [ ] Support Sounds across portals
- [ ] Support particles across portals
- [ ] Adapt vertical portals when players exit them
- [ ] Do not hide entities INSIDE the frame
- [x] Capture fast moving entities (such as arrows)
- [x] Do not teleport entities unless they are inside the frame
- [x] Do not capture player entities for the viewing player
- [ ] Simplistic Configuration system with an "advanced mode" via command to unlock more experimental options
- [x] Portals should save and load automatically
- [x] Keep portal area chunks loaded at all times
- [ ] A Simple command system for listing portals, going to them and more
- [ ] Use permissions with perhaps different ways of creating portals

## Version Support
| Version | Status    |
|---------|-----------|
| 1.7.10  | ~~Not Supported~~   |
| 1.8.X   | 25% Progress   |
| 1.9.X   | **Supported** |
| 1.10.X  | 80% Progress   |
| 1.11.X  | 80% Progress   |
| 1.12.X  | 50% Progress   |

## Plugin Compatability
| Plugin      | Interaction                                |
|-------------|--------------------------------------------|
| ProtocolLib | **Required**                                   |
| WorldEdit   | Planned (Selection)                        |
| WorldGuard  | Planned (Protection)                       |
| Vault       | Planned (Economy & Per Portal Permissions) |
| ViaVersion  | ~~Untested~~                                   |
