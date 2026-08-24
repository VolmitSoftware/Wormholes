package art.arcane.wormholes.door;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Thread-safe mutable owner of dimensional-door identity state.
 *
 * <p>Every mutation is persisted before it replaces visible in-memory state.
 * Core identity changes use a complete atomic snapshot, while return tickets
 * use isolated per-player atomic files.</p>
 */
public final class DoorStateService {
    private final DimensionalDoorRepository repository;

    private DoorRegistry registry;
    private PocketAllocator allocator;
    private LinkedHashMap<UUID, DoorPairIdentity> pairsById;
    private LinkedHashMap<UUID, ReturnTicket> ticketsByPlayer;
    private LinkedHashMap<UUID, DoorAccessRecord> accessByItem;

    private DoorStateService(DimensionalDoorRepository repository, DoorStoreSnapshot snapshot) {
        this.repository = Objects.requireNonNull(repository, "repository");
        registry = new DoorRegistry(snapshot.endpoints());
        allocator = PocketAllocator.restore(snapshot);
        pairsById = indexPairs(snapshot.pairs());
        ticketsByPlayer = indexTickets(snapshot.returnTickets());
        accessByItem = indexAccessRecords(snapshot.accessRecords());
    }

    public static DoorStateService load(DimensionalDoorRepository repository) throws IOException {
        Objects.requireNonNull(repository, "repository");
        return new DoorStateService(repository, repository.load());
    }

    public static DoorStateService under(Path pluginDataDirectory) throws IOException {
        return load(DimensionalDoorRepository.under(pluginDataDirectory));
    }

    public synchronized Optional<PlacedDoorEndpoint> findEndpoint(DoorPosition position) {
        return registry.at(Objects.requireNonNull(position, "position"));
    }

    public synchronized Optional<PlacedDoorEndpoint> findEndpoint(UUID worldId, int x, int y, int z) {
        return registry.at(Objects.requireNonNull(worldId, "worldId"), x, y, z);
    }

    public synchronized Optional<PlacedDoorEndpoint> findEndpointByItem(UUID itemId) {
        return registry.byItemId(Objects.requireNonNull(itemId, "itemId"));
    }

    public synchronized Optional<PlacedDoorEndpoint> findPairedEndpoint(UUID pairId, PairEndpoint endpoint) {
        return registry.pairedEndpoint(
            Objects.requireNonNull(pairId, "pairId"),
            Objects.requireNonNull(endpoint, "endpoint")
        );
    }

    public synchronized Optional<PlacedDoorEndpoint> findMate(DoorItemIdentity identity) {
        return registry.mateOf(Objects.requireNonNull(identity, "identity"));
    }

    public synchronized Optional<DoorPairIdentity> findPair(UUID pairId) {
        return Optional.ofNullable(pairsById.get(Objects.requireNonNull(pairId, "pairId")));
    }

    /**
     * Registers a newly minted pair before either item can be placed.
     *
     * @return true when added, false when this exact pair was already registered
     */
    public synchronized boolean registerPair(DoorPairIdentity pair) throws IOException {
        Objects.requireNonNull(pair, "pair");
        DoorPairIdentity existing = pairsById.get(pair.pairId());
        if (pair.equals(existing)) {
            return false;
        }
        if (existing != null) {
            throw new IllegalStateException("pair ID is already registered: " + pair.pairId());
        }

        LinkedHashMap<UUID, DoorPairIdentity> candidatePairs = new LinkedHashMap<>(pairsById);
        candidatePairs.put(pair.pairId(), pair);
        persistAndPublish(registry, allocator, candidatePairs, ticketsByPlayer, accessByItem);
        return true;
    }

    /** A pair can only be forgotten while neither endpoint is placed. */
    public synchronized Optional<DoorPairIdentity> removePair(UUID pairId) throws IOException {
        Objects.requireNonNull(pairId, "pairId");
        DoorPairIdentity existing = pairsById.get(pairId);
        if (existing == null) {
            return Optional.empty();
        }
        if (registry.pairedEndpoint(pairId, PairEndpoint.A).isPresent()
            || registry.pairedEndpoint(pairId, PairEndpoint.B).isPresent()) {
            throw new IllegalStateException("cannot remove a pair with a placed endpoint");
        }

        LinkedHashMap<UUID, DoorPairIdentity> candidatePairs = new LinkedHashMap<>(pairsById);
        candidatePairs.remove(pairId);
        persistAndPublish(registry, allocator, candidatePairs, ticketsByPlayer, accessByItem);
        return Optional.of(existing);
    }

    /**
     * @return true when newly registered, false for an identical replay
     */
    public synchronized boolean registerEndpoint(PlacedDoorEndpoint endpoint) throws IOException {
        Objects.requireNonNull(endpoint, "endpoint");
        DoorRegistry candidateRegistry = copyRegistry();
        if (!candidateRegistry.register(endpoint)) {
            return false;
        }
        persistAndPublish(candidateRegistry, allocator, pairsById, ticketsByPlayer, accessByItem);
        return true;
    }

    public synchronized boolean registerEndpoint(PlacedDoorEndpoint endpoint, UUID ownerId) throws IOException {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(ownerId, "ownerId");
        DoorItemIdentity identity = endpoint.identity();
        DoorRegistry candidateRegistry = copyRegistry();
        if (!candidateRegistry.register(endpoint)) {
            return false;
        }

        LinkedHashMap<UUID, DoorAccessRecord> candidateAccess = accessByItem;
        if (identity.kind() != DoorKind.RETURN && !accessByItem.containsKey(identity.itemId())) {
            candidateAccess = new LinkedHashMap<>(accessByItem);
            candidateAccess.put(identity.itemId(), DoorAccessRecord.unrestricted(identity.itemId(), ownerId));
        }
        persistAndPublish(candidateRegistry, allocator, pairsById, ticketsByPlayer, candidateAccess);
        return true;
    }

    public synchronized boolean relocateEndpoint(
        PlacedDoorEndpoint expected,
        PlacedDoorEndpoint replacement
    ) throws IOException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(replacement, "replacement");
        if (!expected.identity().equals(replacement.identity())) {
            throw new IllegalArgumentException("endpoint relocation must preserve identity");
        }
        PlacedDoorEndpoint current = registry.byItemId(expected.identity().itemId())
            .orElseThrow(() -> new IllegalStateException("endpoint is not registered"));
        if (!current.equals(expected)) {
            throw new IllegalStateException("registered endpoint does not match relocation source");
        }
        if (expected.equals(replacement)) {
            return false;
        }

        DoorRegistry candidateRegistry = copyRegistry();
        candidateRegistry.remove(expected.position())
            .orElseThrow(() -> new IllegalStateException("endpoint relocation source is missing"));
        candidateRegistry.register(replacement);
        persistAndPublish(candidateRegistry, allocator, pairsById, ticketsByPlayer, accessByItem);
        return true;
    }

    public synchronized Optional<PlacedDoorEndpoint> removeEndpoint(DoorPosition position) throws IOException {
        Objects.requireNonNull(position, "position");
        DoorRegistry candidateRegistry = copyRegistry();
        Optional<PlacedDoorEndpoint> removed = candidateRegistry.remove(position);
        if (removed.isEmpty()) {
            return Optional.empty();
        }
        persistAndPublish(candidateRegistry, allocator, pairsById, ticketsByPlayer, accessByItem);
        return removed;
    }

    public synchronized boolean setEndpointOpenState(DoorPosition position, DoorOpenState openState)
        throws IOException {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(openState, "openState");
        PlacedDoorEndpoint existing = registry.at(position).orElse(null);
        if (existing == null || existing.openState() == openState) {
            return false;
        }

        DoorRegistry candidateRegistry = copyRegistry();
        candidateRegistry.remove(position)
            .orElseThrow(() -> new IllegalStateException("endpoint is not registered"));
        candidateRegistry.register(existing.withOpenState(openState));
        persistAndPublish(candidateRegistry, allocator, pairsById, ticketsByPlayer, accessByItem);
        return true;
    }

    public DoorDestination resolveDestination(DoorItemIdentity identity, UUID travelerId) {
        return DoorDestinationResolver.resolve(identity, travelerId);
    }

    /** Resolves PERSONAL/PUBLIC identity and creates its permanent pocket if needed. */
    public synchronized PocketSpace getOrAllocatePocket(DoorItemIdentity identity, UUID travelerId) throws IOException {
        return getOrAllocatePocket(identity, travelerId, PocketShell.defaults());
    }

    public synchronized PocketSpace getOrAllocatePocket(
        DoorItemIdentity identity,
        UUID travelerId,
        PocketShell shell
    ) throws IOException {
        DoorDestination destination = resolveDestination(
            Objects.requireNonNull(identity, "identity"),
            Objects.requireNonNull(travelerId, "travelerId")
        );
        if (!(destination instanceof PocketDoorDestination pocket)) {
            throw new IllegalArgumentException(identity.kind() + " does not resolve to a pocket");
        }
        return getOrAllocatePocket(pocket.binding(), shell);
    }

    public synchronized PocketSpace getOrAllocatePocket(PocketBinding binding) throws IOException {
        return getOrAllocatePocket(binding, PocketShell.defaults());
    }

    /** {@code shell} shapes the pocket only when this call is the one that creates it. */
    public synchronized PocketSpace getOrAllocatePocket(PocketBinding binding, PocketShell shell) throws IOException {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(shell, "shell");
        Optional<PocketSpace> existing = allocator.find(binding);
        if (existing.isPresent()) {
            return existing.get();
        }

        PocketAllocator candidateAllocator = copyAllocator();
        PocketSpace allocated = candidateAllocator.getOrAllocate(binding, shell);
        persistAndPublish(registry, candidateAllocator, pairsById, ticketsByPlayer, accessByItem);
        return allocated;
    }

    /**
     * Persists a new shell for an existing pocket. The caller is responsible for
     * having already reshaped the world to match.
     *
     * @return the stored space after the change
     */
    public synchronized PocketSpace reshapePocket(
        UUID spaceId,
        PocketShell expected,
        PocketShell shell
    ) throws IOException {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(shell, "shell");
        PocketSpace current = allocator.findById(spaceId)
            .orElseThrow(() -> new IllegalArgumentException("unknown pocket space " + spaceId));
        if (!current.shell().equals(expected)) {
            throw new IllegalStateException("pocket shell changed before reshape persistence");
        }
        PocketAllocator candidateAllocator = copyAllocator();
        PocketSpace updated = candidateAllocator.reshape(spaceId, shell);
        persistAndPublish(registry, candidateAllocator, pairsById, ticketsByPlayer, accessByItem);
        return updated;
    }

    public synchronized Optional<PocketSpace> findPocket(PocketBinding binding) {
        return allocator.find(Objects.requireNonNull(binding, "binding"));
    }

    public synchronized Optional<PocketSpace> findPocketById(UUID spaceId) {
        return allocator.findById(Objects.requireNonNull(spaceId, "spaceId"));
    }

    public synchronized Optional<ReturnTicket> getReturnTicket(UUID playerId) {
        return Optional.ofNullable(ticketsByPlayer.get(Objects.requireNonNull(playerId, "playerId")));
    }

    public synchronized void putReturnTicket(ReturnTicket ticket) throws IOException {
        Objects.requireNonNull(ticket, "ticket");
        if (ticket.equals(ticketsByPlayer.get(ticket.playerId()))) {
            return;
        }
        LinkedHashMap<UUID, ReturnTicket> candidateTickets = new LinkedHashMap<>(ticketsByPlayer);
        candidateTickets.put(ticket.playerId(), ticket);
        repository.putReturnTicket(ticket);
        ticketsByPlayer = candidateTickets;
    }

    public synchronized Optional<ReturnTicket> removeReturnTicket(UUID playerId) throws IOException {
        Objects.requireNonNull(playerId, "playerId");
        ReturnTicket existing = ticketsByPlayer.get(playerId);
        if (existing == null) {
            return Optional.empty();
        }
        LinkedHashMap<UUID, ReturnTicket> candidateTickets = new LinkedHashMap<>(ticketsByPlayer);
        candidateTickets.remove(playerId);
        repository.removeReturnTicket(playerId);
        ticketsByPlayer = candidateTickets;
        return Optional.of(existing);
    }

    public synchronized Optional<DoorAccessRecord> accessRecord(UUID itemId) {
        return Optional.ofNullable(accessByItem.get(Objects.requireNonNull(itemId, "itemId")));
    }

    public synchronized boolean setAccessState(UUID itemId, UUID playerId, DoorAccessState state) throws IOException {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(state, "state");
        DoorAccessRecord existing = accessByItem.get(itemId);
        if (existing == null || !existing.isListed(playerId)) {
            return false;
        }
        return publishAccessRecord(existing, existing.withPlayerState(playerId, state));
    }

    public synchronized boolean addAccessPlayer(UUID itemId, UUID playerId) throws IOException {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(playerId, "playerId");
        DoorAccessRecord existing = accessByItem.get(itemId);
        if (existing == null || existing.isListed(playerId)) {
            return false;
        }
        return publishAccessRecord(existing, existing.withPlayerState(playerId, DoorAccessState.NEUTRAL));
    }

    public synchronized boolean removeAccessPlayer(UUID itemId, UUID playerId) throws IOException {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(playerId, "playerId");
        DoorAccessRecord existing = accessByItem.get(itemId);
        if (existing == null) {
            return false;
        }
        return publishAccessRecord(existing, existing.withPlayerRemoved(playerId));
    }

    public synchronized boolean removeAccessRecord(UUID itemId) throws IOException {
        Objects.requireNonNull(itemId, "itemId");
        if (!accessByItem.containsKey(itemId)) {
            return false;
        }
        LinkedHashMap<UUID, DoorAccessRecord> candidateAccess = new LinkedHashMap<>(accessByItem);
        candidateAccess.remove(itemId);
        persistAndPublish(registry, allocator, pairsById, ticketsByPlayer, candidateAccess);
        return true;
    }

    public synchronized List<DoorAccessRecord> accessRecords() {
        return List.copyOf(accessByItem.values());
    }

    public synchronized List<DoorPairIdentity> pairs() {
        return List.copyOf(pairsById.values());
    }

    public synchronized List<PlacedDoorEndpoint> endpoints() {
        return registry.endpoints();
    }

    public synchronized List<PocketSpace> spaces() {
        return allocator.spaces();
    }

    public synchronized List<ReturnTicket> returnTickets() {
        return List.copyOf(ticketsByPlayer.values());
    }

    public synchronized DoorStoreSnapshot snapshot() {
        return buildSnapshot(registry, allocator, pairsById.values(), ticketsByPlayer.values(), accessByItem.values());
    }

    public DimensionalDoorRepository repository() {
        return repository;
    }

    private boolean publishAccessRecord(DoorAccessRecord existing, DoorAccessRecord updated) throws IOException {
        if (updated.equals(existing)) {
            return false;
        }
        LinkedHashMap<UUID, DoorAccessRecord> candidateAccess = new LinkedHashMap<>(accessByItem);
        candidateAccess.put(updated.itemId(), updated);
        persistAndPublish(registry, allocator, pairsById, ticketsByPlayer, candidateAccess);
        return true;
    }

    private void persistAndPublish(
        DoorRegistry candidateRegistry,
        PocketAllocator candidateAllocator,
        LinkedHashMap<UUID, DoorPairIdentity> candidatePairs,
        LinkedHashMap<UUID, ReturnTicket> candidateTickets,
        LinkedHashMap<UUID, DoorAccessRecord> candidateAccess
    ) throws IOException {
        DoorStoreSnapshot candidate = buildSnapshot(
            candidateRegistry,
            candidateAllocator,
            candidatePairs.values(),
            candidateTickets.values(),
            candidateAccess.values()
        );
        repository.saveState(candidate);
        registry = candidateRegistry;
        allocator = candidateAllocator;
        pairsById = candidatePairs;
        ticketsByPlayer = candidateTickets;
        accessByItem = candidateAccess;
    }

    private DoorRegistry copyRegistry() {
        return new DoorRegistry(registry.endpoints());
    }

    private PocketAllocator copyAllocator() {
        return new PocketAllocator(
            allocator.stride(),
            allocator.centerY(),
            allocator.nextSlot(),
            allocator.spaces()
        );
    }

    private static DoorStoreSnapshot buildSnapshot(
        DoorRegistry registry,
        PocketAllocator allocator,
        Collection<DoorPairIdentity> pairs,
        Collection<ReturnTicket> tickets,
        Collection<DoorAccessRecord> accessRecords
    ) {
        return new DoorStoreSnapshot(
            DoorStoreSnapshot.CURRENT_SCHEMA,
            allocator.nextSlot(),
            new ArrayList<>(pairs),
            registry.endpoints(),
            allocator.spaces(),
            new ArrayList<>(tickets),
            new ArrayList<>(accessRecords)
        );
    }

    private static LinkedHashMap<UUID, DoorPairIdentity> indexPairs(List<DoorPairIdentity> pairs) {
        LinkedHashMap<UUID, DoorPairIdentity> indexed = new LinkedHashMap<>();
        for (DoorPairIdentity pair : pairs) {
            indexed.put(pair.pairId(), pair);
        }
        return indexed;
    }

    private static LinkedHashMap<UUID, ReturnTicket> indexTickets(List<ReturnTicket> tickets) {
        LinkedHashMap<UUID, ReturnTicket> indexed = new LinkedHashMap<>();
        for (ReturnTicket ticket : tickets) {
            indexed.put(ticket.playerId(), ticket);
        }
        return indexed;
    }

    private static LinkedHashMap<UUID, DoorAccessRecord> indexAccessRecords(List<DoorAccessRecord> accessRecords) {
        LinkedHashMap<UUID, DoorAccessRecord> indexed = new LinkedHashMap<>();
        for (DoorAccessRecord record : accessRecords) {
            indexed.put(record.itemId(), record);
        }
        return indexed;
    }
}
