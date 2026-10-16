package art.arcane.wormholes.menu.portal;

import java.util.Objects;

import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.portal.NetworkViewQuality;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalTravelMode;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.ProjectionRenderMode;

public sealed interface PortalEdit permits PortalEdit.Name, PortalEdit.Projection, PortalEdit.Type, PortalEdit.Mirror,
    PortalEdit.MirrorRotation, PortalEdit.FrameChange, PortalEdit.PermissionMode, PortalEdit.TravelMode,
    PortalEdit.Quality, PortalEdit.NetworkNumber, PortalEdit.SettingsSync, PortalEdit.Blackout,
    PortalEdit.BlackoutColorChoice, PortalEdit.ActivationRange, PortalEdit.RenderMode, PortalEdit.AmbientStyle,
    PortalEdit.AmbientColor, PortalEdit.ApertureShape, PortalEdit.SurfaceSkin, PortalEdit.FallbackBlock,
    PortalEdit.PublicLookLabel {
    record Name(String name) implements PortalEdit {
        public Name {
            Objects.requireNonNull(name, "name");
        }
    }

    record Projection(ProjectionMode mode) implements PortalEdit {
        public Projection {
            Objects.requireNonNull(mode, "mode");
        }
    }

    record Type(PortalType type) implements PortalEdit {
        public Type {
            Objects.requireNonNull(type, "type");
        }
    }

    record Mirror(boolean enabled) implements PortalEdit {
    }

    record MirrorRotation(boolean clockwise) implements PortalEdit {
    }

    record FrameChange(FrameOperation operation) implements PortalEdit {
        public FrameChange {
            Objects.requireNonNull(operation, "operation");
        }
    }

    record PermissionMode(PortalPermissionMode mode) implements PortalEdit {
        public PermissionMode {
            Objects.requireNonNull(mode, "mode");
        }
    }

    record TravelMode(PortalTravelMode mode) implements PortalEdit {
        public TravelMode {
            Objects.requireNonNull(mode, "mode");
        }
    }

    record Quality(NetworkViewQuality quality) implements PortalEdit {
        public Quality {
            Objects.requireNonNull(quality, "quality");
        }
    }

    record NetworkNumber(NetworkNumberField field, int value) implements PortalEdit {
        public NetworkNumber {
            Objects.requireNonNull(field, "field");
        }
    }

    record SettingsSync(boolean enabled) implements PortalEdit {
    }

    record Blackout(boolean enabled) implements PortalEdit {
    }

    record BlackoutColorChoice(BlackoutColor color) implements PortalEdit {
        public BlackoutColorChoice {
            Objects.requireNonNull(color, "color");
        }
    }

    record ActivationRange(int blocks) implements PortalEdit {
    }

    record RenderMode(ProjectionRenderMode mode) implements PortalEdit {
        public RenderMode {
            Objects.requireNonNull(mode, "mode");
        }
    }

    record AmbientStyle(AmbientParticleStyle style) implements PortalEdit {
        public AmbientStyle {
            Objects.requireNonNull(style, "style");
        }
    }

    record AmbientColor(int rgb) implements PortalEdit {
    }

    record ApertureShape(ShapeDescriptor shape) implements PortalEdit {
        public ApertureShape {
            Objects.requireNonNull(shape, "shape");
        }
    }

    record SurfaceSkin(String skin) implements PortalEdit {
        public SurfaceSkin {
            Objects.requireNonNull(skin, "skin");
        }
    }

    record FallbackBlock(String blockState) implements PortalEdit {
        public FallbackBlock {
            Objects.requireNonNull(blockState, "blockState");
        }
    }

    record PublicLookLabel(boolean enabled) implements PortalEdit {
    }
}
