# Changelog

## x.x.x

### Added

- Single-jar support for Paper 26.1.2 and 26.2: the projection blackout renderer now activates on both versions (BlockDisplay metadata layout verified identical between the two).
- Same-version network enforcement: a peer link is rejected during the handshake when the remote server runs a different Minecraft version, with a single log line naming both versions. Mixed-version links would misdecode entity-metadata blobs and block-state strings on the wire.

### Changed

- Compile baseline lowered to paper-api 26.1.2.build.74-stable; plugin.yml and paper-plugin.yml api-version lowered to 26.1.
- Pocket dimension datapack accepts data pack formats 101.1 (26.1.2) through 107 (26.2) instead of pinning 107 only.
- Splash screen reports MC support as 26.1.2 - 26.2.
- Operator documentation now lives under the central `docs/wormholes/` wiki source and matches runtime: kebab-case TOML keys, one-way any-world same-class links, `/wh server connect` plus `/wh server <name>` shorthand, network status auto-doctor, READY wire peer counts, 12 declared yml permission nodes plus dynamic `wormholes.portal.<sanitized-name>`, Java 25 on Paper/Purpur/Folia 26.1.2–26.2 with Spigot 26.2 fallbacks, and zstd via SlimJar plus `plugin.yml` libraries.
- Dimensional door reskin accepts any vanilla hinged door, including iron and copper. Public/Pair/Personal defaults stay Oak / Dark Oak / Pale Oak. Existing iron doors are no longer rewritten to Pale Oak on startup.

### Fixed

- Documented vanilla pending coverage (travel cancelled as soon as create/eye-place registers pending cells), wand apertures from live blocks, RTP type-switch force-close until READY or a new tunnel, player-only portal whitelist/blacklist, separate orientation CW/CCW buttons vs Mirror right/shift-right rotate, crafter identity mint block, redstone-held Open-door consume-until-close, access-gated door break, `/wh debug` cleared on reload to match `verbose-logging`, `foveated-unrendering` as the look/side interest filter, unlimited chunk send/load at `<=0` or `>10000`, and reload application of `compression-retrain-interval-sec` and `capture-zone-radius`.
