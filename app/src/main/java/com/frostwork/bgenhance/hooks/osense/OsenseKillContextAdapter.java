package com.frostwork.bgenhance.hooks.osense;

import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.residency.decision.KillContext;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Classifies an OSense kill using only the callback's in-memory context. */
public final class OsenseKillContextAdapter {
    private static final int CONTEXT_TYPE_NIRVANA = 3;
    private static final int CONTEXT_TYPE_CAMERA_LOW_FREE = 6;
    private static final int CONTEXT_TYPE_GAME_LOW_FREE = 7;
    private static final int MIN_CONTEXT_TYPE = 0;
    private static final int MAX_CONTEXT_TYPE = 9;
    private static final int DEFAULT_SUB_REASON = 1024;
    private static final int NIRVANA_SUB_REASON = 1026;
    private static final int CAMERA_SUB_REASON = 1028;
    private static final int GAME_SUB_REASON = 1029;

    private static final ConcurrentMap<Class<?>, AccessorSet> ACCESSORS =
            new ConcurrentHashMap<>();

    private OsenseKillContextAdapter() {}

    public static boolean prepare(Class<?> contextType) {
        return contextType != null && accessors(contextType).isReady();
    }

    public static Resolution resolve(Object context, int subReasonCode) {
        if (context == null) return Resolution.failed(Failure.NULL_CONTEXT);
        if (subReasonCode < 0) return Resolution.failed(Failure.INVALID_SUB_REASON);

        AccessorSet set = accessors(context.getClass());
        if (!set.isReady()) return Resolution.failed(set.failure);
        try {
            int contextType = set.accessors.contextType.getInt(context);
            Object sceneValue = set.accessors.scene.get(context);
            if (sceneValue != null && !(sceneValue instanceof String)) {
                return Resolution.failed(Failure.INVALID_SCENE);
            }
            return classify(contextType, (String) sceneValue, subReasonCode);
        } catch (IllegalAccessException | RuntimeException error) {
            return Resolution.failed(Failure.REFLECTION_FAILED);
        }
    }

    private static Resolution classify(int contextType, String scene, int subReasonCode) {
        if (contextType < MIN_CONTEXT_TYPE || contextType > MAX_CONTEXT_TYPE) {
            return Resolution.failed(Failure.INVALID_CONTEXT_TYPE);
        }
        if ("NirvanaLowFree".equals(scene)) {
            return specific(contextType, CONTEXT_TYPE_NIRVANA, -1,
                    scene, subReasonCode, NIRVANA_SUB_REASON,
                    FeatureIds.NIRVANA, ModuleSettings.KEY_NIRVANA);
        }
        if ("CamLowFree".equals(scene)) {
            return specific(contextType, CONTEXT_TYPE_NIRVANA, CONTEXT_TYPE_CAMERA_LOW_FREE,
                    scene, subReasonCode, CAMERA_SUB_REASON,
                    FeatureIds.CAMERA_LOW_FREE, ModuleSettings.KEY_CAMERA_LOW_FREE);
        }
        if ("GameLowFree".equals(scene)) {
            return specific(contextType, CONTEXT_TYPE_NIRVANA, CONTEXT_TYPE_GAME_LOW_FREE,
                    scene, subReasonCode, GAME_SUB_REASON,
                    FeatureIds.GAME_LOW_FREE, ModuleSettings.KEY_GAME_LOW_FREE);
        }
        if (isSpecificContextType(contextType) || isSpecificSubReason(subReasonCode)) {
            return Resolution.failed(Failure.CONTEXT_MISMATCH);
        }
        return Resolution.resolved(new Mapping(
                FeatureIds.OSENSE_PER_PROCESS,
                KillContext.Path.OSENSE_PER_PROCESS,
                "",
                subReasonCode,
                scene));
    }

    private static Resolution specific(
            int contextType,
            int primaryContextType,
            int alternateContextType,
            String scene,
            int subReasonCode,
            int expectedSubReason,
            String featureId,
            String featureKey) {
        if ((contextType != primaryContextType && contextType != alternateContextType)
                || (subReasonCode != DEFAULT_SUB_REASON
                && subReasonCode != expectedSubReason)) {
            return Resolution.failed(Failure.CONTEXT_MISMATCH);
        }
        return Resolution.resolved(new Mapping(
                featureId,
                KillContext.Path.NIRVANA,
                featureKey,
                subReasonCode,
                scene));
    }

    private static boolean isSpecificSubReason(int subReasonCode) {
        return subReasonCode == NIRVANA_SUB_REASON
                || subReasonCode == CAMERA_SUB_REASON
                || subReasonCode == GAME_SUB_REASON;
    }

    private static boolean isSpecificContextType(int contextType) {
        return contextType == CONTEXT_TYPE_NIRVANA
                || contextType == CONTEXT_TYPE_CAMERA_LOW_FREE
                || contextType == CONTEXT_TYPE_GAME_LOW_FREE;
    }

    private static AccessorSet accessors(Class<?> type) {
        AccessorSet cached = ACCESSORS.get(type);
        if (cached != null) return cached;
        for (Class<?> current = type.getSuperclass();
                current != null;
                current = current.getSuperclass()) {
            AccessorSet inherited = ACCESSORS.get(current);
            if (inherited != null && inherited.isReady()) {
                AccessorSet raced = ACCESSORS.putIfAbsent(type, inherited);
                return raced != null ? raced : inherited;
            }
        }
        AccessorSet loaded = load(type);
        AccessorSet raced = ACCESSORS.putIfAbsent(type, loaded);
        return raced != null ? raced : loaded;
    }

    private static AccessorSet load(Class<?> type) {
        try {
            return AccessorSet.ready(new Accessors(
                    findField(type, "contextType"),
                    findField(type, "scene")));
        } catch (NoSuchFieldException error) {
            return AccessorSet.failed(Failure.ACCESSOR_MISSING);
        } catch (RuntimeException error) {
            return AccessorSet.failed(Failure.REFLECTION_FAILED);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // KillContext fields are inherited by each concrete OSense scene context.
            }
        }
        throw new NoSuchFieldException(type.getName() + '#' + name);
    }

    public enum Failure {
        NULL_CONTEXT,
        ACCESSOR_MISSING,
        REFLECTION_FAILED,
        INVALID_CONTEXT_TYPE,
        INVALID_SUB_REASON,
        INVALID_SCENE,
        CONTEXT_MISMATCH
    }

    public static final class Mapping {
        private final String featureId;
        private final KillContext.Path path;
        private final String featureKey;
        private final int code;
        private final String scene;

        private Mapping(
                String featureId,
                KillContext.Path path,
                String featureKey,
                int code,
                String scene) {
            this.featureId = featureId;
            this.path = path;
            this.featureKey = featureKey;
            this.code = code;
            this.scene = scene == null ? "" : scene;
        }

        public String featureId() { return featureId; }
        public KillContext.Path path() { return path; }
        public String featureKey() { return featureKey; }
        public boolean hasFeatureGate() { return !featureKey.isEmpty(); }
        public int code() { return code; }
        public String scene() { return scene; }
    }

    public static final class Resolution {
        private final Mapping mapping;
        private final Failure failure;

        private Resolution(Mapping mapping, Failure failure) {
            this.mapping = mapping;
            this.failure = failure;
        }

        private static Resolution resolved(Mapping mapping) {
            return new Resolution(mapping, null);
        }

        private static Resolution failed(Failure failure) {
            return new Resolution(null, failure);
        }

        public boolean isResolved() { return mapping != null; }
        public Mapping mappingOrNull() { return mapping; }
        public Failure failureOrNull() { return failure; }
    }

    private static final class Accessors {
        private final Field contextType;
        private final Field scene;

        private Accessors(Field contextType, Field scene) {
            this.contextType = contextType;
            this.scene = scene;
        }
    }

    private static final class AccessorSet {
        private final Accessors accessors;
        private final Failure failure;

        private AccessorSet(Accessors accessors, Failure failure) {
            this.accessors = accessors;
            this.failure = failure;
        }

        private static AccessorSet ready(Accessors accessors) {
            return new AccessorSet(accessors, null);
        }

        private static AccessorSet failed(Failure failure) {
            return new AccessorSet(null, failure);
        }

        private boolean isReady() { return accessors != null; }
    }
}
