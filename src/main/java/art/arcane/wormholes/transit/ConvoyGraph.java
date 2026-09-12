package art.arcane.wormholes.transit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

/**
 * The physically attached rig around one entity: vehicle chain, passengers, and leash edges. Members are
 * listed in dependency order (vehicles before riders, holders before leashed mobs) so they can be moved
 * and re-attached in one pass. The walk stops once it exceeds {@code maxEntities} and reports overflow.
 */
public final class ConvoyGraph {
    private static final double FIT_EPSILON = 1.0E-6D;

    /** One rig member with the attachments it depends on (null when the member is a base). */
    public record Member(Entity entity, Entity vehicle, Entity leashHolder) {
    }

    private final List<Member> members;
    private final Entity root;
    private final boolean overflow;
    private final int playerCount;
    private final Set<UUID> ids;

    private ConvoyGraph(List<Member> members, boolean overflow) {
        this.members = List.copyOf(members);
        this.overflow = overflow;
        Entity firstPlayer = null;
        int players = 0;
        Set<UUID> memberIds = new HashSet<UUID>();
        for (Member member : this.members) {
            memberIds.add(member.entity().getUniqueId());
            if (member.entity() instanceof Player) {
                players++;
                if (firstPlayer == null) {
                    firstPlayer = member.entity();
                }
            }
        }
        this.root = firstPlayer != null ? firstPlayer : this.members.getFirst().entity();
        this.playerCount = players;
        this.ids = Set.copyOf(memberIds);
    }

    /**
     * Walks vehicle, passenger, and leash links from {@code seed}. {@code candidates} supplies the nearby
     * entities that may be leashed to a member (Bukkit has no reverse leash lookup); leash holders are
     * pulled in even when they are not candidates.
     */
    public static ConvoyGraph closure(Entity seed, Collection<? extends Entity> candidates, int maxEntities) {
        int cap = Math.max(1, maxEntities);
        Map<UUID, Entity> found = new LinkedHashMap<UUID, Entity>();
        ArrayDeque<Entity> queue = new ArrayDeque<Entity>();
        found.put(seed.getUniqueId(), seed);
        queue.add(seed);
        boolean overflow = false;
        while (!queue.isEmpty() && !overflow) {
            Entity current = queue.poll();
            for (Entity linked : linkedTo(current, candidates)) {
                if (linked == null || found.containsKey(linked.getUniqueId())) {
                    continue;
                }
                found.put(linked.getUniqueId(), linked);
                if (found.size() > cap) {
                    overflow = true;
                    break;
                }
                queue.add(linked);
            }
        }
        return new ConvoyGraph(order(found), overflow);
    }

    private static List<Entity> linkedTo(Entity current, Collection<? extends Entity> candidates) {
        List<Entity> linked = new ArrayList<Entity>();
        Entity vehicle = current.getVehicle();
        if (vehicle != null) {
            linked.add(vehicle);
        }
        linked.addAll(current.getPassengers());
        Entity holder = leashHolderOf(current);
        if (holder != null) {
            linked.add(holder);
        }
        if (candidates != null) {
            UUID currentId = current.getUniqueId();
            for (Entity candidate : candidates) {
                if (candidate == null || candidate.getUniqueId().equals(currentId)) {
                    continue;
                }
                Entity candidateHolder = leashHolderOf(candidate);
                if (candidateHolder != null && candidateHolder.getUniqueId().equals(currentId)) {
                    linked.add(candidate);
                }
            }
        }
        return linked;
    }

    static Entity leashHolderOf(Entity entity) {
        if (!(entity instanceof LivingEntity living) || !WormholesPlatform.isLeashed(living)) {
            return null;
        }
        try {
            return living.getLeashHolder();
        } catch (IllegalStateException unleashedMeanwhile) {
            return null;
        }
    }

    private static List<Member> order(Map<UUID, Entity> found) {
        List<Member> discovered = new ArrayList<Member>(found.size());
        Map<UUID, Integer> pending = new LinkedHashMap<UUID, Integer>();
        for (Entity entity : found.values()) {
            Entity vehicle = inSet(entity.getVehicle(), found);
            Entity holder = inSet(leashHolderOf(entity), found);
            discovered.add(new Member(entity, vehicle, holder));
            pending.put(entity.getUniqueId(), (vehicle == null ? 0 : 1) + (holder == null ? 0 : 1));
        }
        List<Member> ordered = new ArrayList<Member>(discovered.size());
        ArrayDeque<Member> ready = new ArrayDeque<Member>();
        for (Member member : discovered) {
            if (pending.get(member.entity().getUniqueId()) == 0) {
                ready.add(member);
            }
        }
        Set<UUID> emitted = new HashSet<UUID>();
        while (!ready.isEmpty()) {
            Member member = ready.poll();
            UUID id = member.entity().getUniqueId();
            if (!emitted.add(id)) {
                continue;
            }
            ordered.add(member);
            for (Member dependent : discovered) {
                UUID dependentId = dependent.entity().getUniqueId();
                if (emitted.contains(dependentId)) {
                    continue;
                }
                int remaining = pending.get(dependentId);
                if (dependent.vehicle() != null && dependent.vehicle().getUniqueId().equals(id)) {
                    remaining--;
                }
                if (dependent.leashHolder() != null && dependent.leashHolder().getUniqueId().equals(id)) {
                    remaining--;
                }
                pending.put(dependentId, remaining);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }
        for (Member member : discovered) {
            if (!emitted.contains(member.entity().getUniqueId())) {
                ordered.add(member);
            }
        }
        return ordered;
    }

    private static Entity inSet(Entity entity, Map<UUID, Entity> found) {
        return entity != null && found.containsKey(entity.getUniqueId()) ? entity : null;
    }

    public List<Member> members() {
        return members;
    }

    /** The member whose crossing commits the rig: the first player in dependency order, else the base. */
    public Entity root() {
        return root;
    }

    public int size() {
        return members.size();
    }

    public boolean isRig() {
        return members.size() > 1;
    }

    public boolean overflow() {
        return overflow;
    }

    public int playerCount() {
        return playerCount;
    }

    public boolean contains(UUID entityId) {
        return ids.contains(entityId);
    }

    /** The rig's union bounding box must fit the aperture along the frame's right and up axes. */
    public boolean fits(PortalStructure structure, PortalFrame frame) {
        AxisAlignedBB area = structure == null ? null : structure.getArea();
        if (area == null || frame == null) {
            return true;
        }
        BoundingBox union = null;
        for (Member member : members) {
            BoundingBox box = member.entity().getBoundingBox();
            if (box == null) {
                continue;
            }
            union = union == null ? box.clone() : union.union(box);
        }
        if (union == null) {
            return true;
        }
        return extent(union, frame.getRight()) <= aperture(area, frame.getRight()) + FIT_EPSILON
            && extent(union, frame.getUp()) <= aperture(area, frame.getUp()) + FIT_EPSILON;
    }

    /** Every member is inside the source capture zone of {@code world}, so the whole rig can commit this tick. */
    public boolean allInsidePlane(World world, AxisAlignedBB captureZone) {
        if (captureZone == null) {
            return true;
        }
        for (Member member : members) {
            Location location = member.entity().getLocation();
            if (location == null || location.getWorld() == null || world == null || !location.getWorld().equals(world)) {
                return false;
            }
            if (!captureZone.contains(location.toVector())) {
                return false;
            }
        }
        return true;
    }

    private static double extent(BoundingBox box, Direction axis) {
        if (axis.x() != 0) {
            return box.getWidthX();
        }
        if (axis.y() != 0) {
            return box.getHeight();
        }
        return box.getWidthZ();
    }

    /** Structure areas span block corners (max corner sits at +0.999), so the block extent is the ceiling of the span. */
    private static double aperture(AxisAlignedBB area, Direction axis) {
        double size = axis.x() != 0 ? area.sizeX() : axis.y() != 0 ? area.sizeY() : area.sizeZ();
        return Math.max(1.0D, Math.ceil(size - 1.0E-9D));
    }
}
