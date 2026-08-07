# Changelog

## x.x.x

### Added

- Single-jar support for Paper 26.1.2 and 26.2: the projection blackout renderer now activates on both versions (BlockDisplay metadata layout verified identical between the two).
- Same-version network enforcement: a peer link is rejected during the handshake when the remote server runs a different Minecraft version, with a single log line naming both versions. Mixed-version links would misdecode entity-metadata blobs and block-state strings on the wire.

### Changed

- Compile baseline lowered to paper-api 26.1.2.build.74-stable; plugin.yml and paper-plugin.yml api-version lowered to 26.1.
- Pocket dimension datapack accepts data pack formats 101.1 (26.1.2) through 107 (26.2) instead of pinning 107 only.
- Splash screen reports MC support as 26.1.2 - 26.2.
