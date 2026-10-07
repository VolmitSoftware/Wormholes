package art.arcane.optics.scan;

public record PassPlan(Kind kind, int flags, int reasons, long revision) {
    public static final int DESTINATION_CONTENT_STALE = 1;
    public static final int DESTINATION_SAMPLES_STALE = 1 << 1;
    public static final int LOCAL_CONTENT_RESAMPLE = 1 << 2;
    public static final int CONTENT_INVALIDATED = 1 << 3;
    public static final int FULL_SEND = 1 << 4;
    public static final int REFRESH_VISIBILITY = 1 << 5;
    public static final PassPlan REUSE = new PassPlan(Kind.REUSE, 0, 0, 0L);

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public boolean resumes() {
        return kind == Kind.RESUME_OCCLUSION;
    }

    public enum Kind {
        REUSE,
        RESUME_OCCLUSION,
        RESCAN
    }
}
