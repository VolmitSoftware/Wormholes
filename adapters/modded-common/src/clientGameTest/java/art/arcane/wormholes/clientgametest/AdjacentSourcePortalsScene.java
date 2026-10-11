package art.arcane.wormholes.clientgametest;

import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Vec3d;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

record AdjacentSourcePortalsScene(List<Endpoint> endpoints, List<Wall> walls, List<Cuboid> opaque) {
    AdjacentSourcePortalsScene {
        endpoints = List.copyOf(endpoints);
        walls = List.copyOf(walls);
        opaque = List.copyOf(opaque);
        if (endpoints.size() != 4 || endpoints.stream().map(Endpoint::id).distinct().count() != 4) {
            throw new AssertionError("Adjacent scene requires four distinct portal endpoints");
        }
    }

    Endpoint endpoint(UUID id) {
        for (Endpoint endpoint : endpoints) {
            if (endpoint.id().equals(id)) {
                return endpoint;
            }
        }
        throw new AssertionError("Unknown scene endpoint " + id);
    }

    Sample sample(String world, Vec3d eye, Vec3d ray) {
        return sample(world, eye, ray, 0, null);
    }

    private Sample sample(String world, Vec3d eye, Vec3d ray, int depth, UUID firstPortal) {
        Surface nearest = null;
        for (Wall wall : walls) {
            if (!wall.world().equals(world)) {
                continue;
            }
            double component = ray.component(wall.axis());
            if (Math.abs(component) < 0.000001D) {
                continue;
            }
            double distance = (wall.coordinate() + (component < 0.0D ? 1.0D : 0.0D) - eye.component(wall.axis())) / component;
            if (distance <= 0.00001D || nearest != null && distance >= nearest.distance()) {
                continue;
            }
            Vec3d hit = eye.add(ray.multiply(distance));
            int tangent = wall.axis() == 0 ? 2 : 0;
            double u = hit.component(tangent);
            double v = hit.y();
            if (u < wall.minimum() || u >= wall.maximum() || v < wall.bottom() || v >= wall.top()) {
                continue;
            }
            int column = (int) Math.floor(u);
            int row = (int) Math.floor(v);
            boolean interior = u - column > 0.15D && u - column < 0.85D && v - row > 0.15D && v - row < 0.85D;
            nearest = new Surface(distance, interior ? wall.color(column, row) : -1);
        }
        double obstruction = Double.POSITIVE_INFINITY;
        for (Cuboid box : opaque) {
            if (box.world().equals(world)) {
                obstruction = Math.min(obstruction, box.intersection(eye, ray));
            }
        }
        Endpoint opening = null;
        double portalDistance = Double.POSITIVE_INFINITY;
        boolean edge = false;
        for (Endpoint endpoint : endpoints) {
            if (!endpoint.world().equals(world) || Math.abs(ray.z()) < 0.000001D) {
                continue;
            }
            double distance = (endpoint.origin().z() - eye.z()) / ray.z();
            if (distance <= 0.001D || distance >= portalDistance) {
                continue;
            }
            Vec3d hit = eye.add(ray.multiply(distance));
            double u = hit.x() - endpoint.minimum().getX();
            double v = hit.y() - endpoint.minimum().getY();
            if (u < 0.0D || u > 3.0D || v < 0.0D || v > 3.0D) {
                continue;
            }
            portalDistance = distance;
            opening = endpoint;
            edge = u < 0.12D || u > 2.88D || v < 0.12D || v > 2.88D;
        }
        double wallDistance = nearest == null ? Double.POSITIVE_INFINITY : nearest.distance();
        if (obstruction < Math.min(wallDistance, portalDistance)) {
            return null;
        }
        if (opening != null && portalDistance < wallDistance) {
            if (edge || depth >= 3) {
                return null;
            }
            Vec3d intersection = eye.add(ray.multiply(portalDistance));
            boolean front = eye.subtract(opening.origin()).dot(opening.normal()) > 0.0D;
            OpticTransform transform = front ? opening.front() : opening.back();
            Vec3d direction = transform.vector(ray).normalize();
            Vec3d nextEye = transform.point(intersection).add(direction.multiply(0.002D));
            return sample(endpoint(opening.destination()).world(), nextEye, direction, depth + 1,
                firstPortal == null ? opening.id() : firstPortal);
        }
        return nearest == null || nearest.color() < 0 ? null : new Sample(nearest.color(), firstPortal, depth);
    }

    record Endpoint(UUID id, UUID destination, String world, BlockPos minimum, Vec3d origin, Vec3d normal,
                    OpticTransform front, OpticTransform back) {
        Endpoint {
            Objects.requireNonNull(id);
            Objects.requireNonNull(destination);
            Objects.requireNonNull(world);
        }
    }

    record Wall(String world, int axis, int coordinate, int minimum, int maximum, int bottom, int top, int palette, int offset) {
        int color(int column, int row) {
            return palette + Math.floorMod(column + row * 2 + offset, 3);
        }
    }

    record Cuboid(String world, int x, int y, int z, int width, int height, int length) {
        double intersection(Vec3d eye, Vec3d direction) {
            double entry = 0.0D;
            double exit = Double.POSITIVE_INFINITY;
            for (int axis = 0; axis < 3; axis++) {
                double min = switch (axis) {
                    case 0 -> x;
                    case 1 -> y;
                    default -> z;
                };
                double size = switch (axis) {
                    case 0 -> width;
                    case 1 -> height;
                    default -> length;
                };
                double component = direction.component(axis);
                if (Math.abs(component) < 0.000001D) {
                    if (eye.component(axis) < min || eye.component(axis) > min + size) {
                        return Double.POSITIVE_INFINITY;
                    }
                    continue;
                }
                double first = (min - eye.component(axis)) / component;
                double second = (min + size - eye.component(axis)) / component;
                entry = Math.max(entry, Math.min(first, second));
                exit = Math.min(exit, Math.max(first, second));
            }
            return exit > entry + 0.000001D ? entry : Double.POSITIVE_INFINITY;
        }
    }

    record Sample(int color, UUID firstPortal, int depth) {
    }

    private record Surface(double distance, int color) {
    }
}
