package art.arcane.wormholes.chunk.presend;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class NmsMemberCacheTest {
    @Test
    void aHierarchyIsScannedOnceRatherThanOncePerChunkInsideTheCommitmentWindow() {
        AtomicInteger scans = new AtomicInteger();
        NmsMemberCache<Method> cache = new NmsMemberCache<>();
        NmsMemberCache.Resolver<Method> resolver = owner -> {
            scans.incrementAndGet();
            return declaredMethod(owner, "getHandle");
        };

        for (int chunk = 0; chunk < 49; chunk++) {
            cache.get(SampleHandleOwner.class, resolver);
        }

        assertEquals(1, scans.get(), "a 49 chunk burst must not re-walk the NMS class hierarchy 49 times");
    }

    @Test
    void theCachedMemberIsTheSameObjectAndNotAFreshReflectiveCopy() {
        NmsMemberCache<Method> cache = new NmsMemberCache<>();
        NmsMemberCache.Resolver<Method> resolver = owner -> declaredMethod(owner, "getHandle");

        Method first = cache.get(SampleHandleOwner.class, resolver);
        Method second = cache.get(SampleHandleOwner.class, resolver);

        assertNotSame(
            declaredMethod(SampleHandleOwner.class, "getHandle"),
            declaredMethod(SampleHandleOwner.class, "getHandle"),
            "getDeclaredMethods hands back a fresh copy every call, which is the cost being cached away"
        );
        assertSame(first, second);
    }

    @Test
    void aDifferentOwnerClassIsResolvedFreshRatherThanServedTheWrongMember() {
        AtomicInteger scans = new AtomicInteger();
        NmsMemberCache<Field> cache = new NmsMemberCache<>();
        NmsMemberCache.Resolver<Field> resolver = owner -> {
            scans.incrementAndGet();
            return declaredField(owner, "connection");
        };

        Field first = cache.get(SampleConnectionOwner.class, resolver);
        Field second = cache.get(OtherConnectionOwner.class, resolver);
        Field third = cache.get(SampleConnectionOwner.class, resolver);

        assertEquals(SampleConnectionOwner.class, first.getDeclaringClass());
        assertEquals(OtherConnectionOwner.class, second.getDeclaringClass());
        assertEquals(SampleConnectionOwner.class, third.getDeclaringClass());
        assertEquals(3, scans.get());
    }

    @Test
    void aMissingMemberIsRememberedSoAnUnsupportedRuntimeDoesNotRescanOnEveryChunk() {
        AtomicInteger scans = new AtomicInteger();
        NmsMemberCache<Method> cache = new NmsMemberCache<>();
        NmsMemberCache.Resolver<Method> resolver = owner -> {
            scans.incrementAndGet();
            return null;
        };

        assertNull(cache.get(SampleHandleOwner.class, resolver));
        assertNull(cache.get(SampleHandleOwner.class, resolver));
        assertNull(cache.get(SampleHandleOwner.class, resolver));

        assertEquals(1, scans.get());
    }

    @Test
    void aNullOwnerNeverReachesTheResolver() {
        AtomicInteger scans = new AtomicInteger();
        NmsMemberCache<Method> cache = new NmsMemberCache<>();

        assertNull(cache.get(null, owner -> {
            scans.incrementAndGet();
            return null;
        }));

        assertEquals(0, scans.get());
    }

    private static Method declaredMethod(Class<?> owner, String name) {
        for (Method method : owner.getDeclaredMethods()) {
            if (name.equals(method.getName())) {
                return method;
            }
        }
        return null;
    }

    private static Field declaredField(Class<?> owner, String name) {
        for (Field field : owner.getDeclaredFields()) {
            if (name.equals(field.getName())) {
                return field;
            }
        }
        return null;
    }

    private static final class SampleHandleOwner {
        Object getHandle() {
            return this;
        }
    }

    private static final class SampleConnectionOwner {
        private Object connection;
    }

    private static final class OtherConnectionOwner {
        private Object connection;
    }
}
