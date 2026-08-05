package com.frostwork.bgenhance.hooks.osense;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Extracts an OSense process identity using only fields already present in the kill callback.
 * Reflection metadata is cached per runtime class; resolution performs no external lookup or I/O.
 */
public final class OsenseProcessIdentityAdapter {
    private static final AccessorCache<ProcessInfoAccessors> PROCESS_INFO_ACCESSORS =
            new AccessorCache<>(ProcessInfoAccessors::create);
    private static final AccessorCache<GroupAccessors> GROUP_ACCESSORS =
            new AccessorCache<>(GroupAccessors::create);
    private static final AccessorCache<ProcessRecordAccessors> PROCESS_RECORD_ACCESSORS =
            new AccessorCache<>(ProcessRecordAccessors::create);
    private static final AccessorCache<ApplicationInfoAccessors> APPLICATION_INFO_ACCESSORS =
            new AccessorCache<>(ApplicationInfoAccessors::create);

    private OsenseProcessIdentityAdapter() {}

    /** Resolves and caches every declared accessor before a system lock callback can run. */
    public static boolean prepare(Class<?> processInfoType) {
        if (processInfoType == null) return false;
        AccessorSet<ProcessInfoAccessors> processInfoSet =
                PROCESS_INFO_ACCESSORS.get(processInfoType);
        if (!processInfoSet.isReady()) return false;

        ProcessInfoAccessors processInfo = processInfoSet.accessors;
        AccessorSet<GroupAccessors> groupSet =
                GROUP_ACCESSORS.get(processInfo.group.getType());
        AccessorSet<ProcessRecordAccessors> processRecordSet =
                PROCESS_RECORD_ACCESSORS.get(processInfo.processRecord.getType());
        if (!groupSet.isReady() || !processRecordSet.isReady()) return false;
        return APPLICATION_INFO_ACCESSORS.get(
                processRecordSet.accessors.applicationInfo.getType()).isReady();
    }

    public static OsenseProcessIdentityResolution resolve(Object processInfo) {
        if (processInfo == null) {
            return unresolved(OsenseProcessIdentityFailure.NULL_PROCESS_INFO);
        }

        AccessorSet<ProcessInfoAccessors> processInfoSet =
                PROCESS_INFO_ACCESSORS.get(processInfo.getClass());
        if (!processInfoSet.isReady()) return unresolved(processInfoSet.failure);

        try {
            ProcessInfoAccessors processAccessors = processInfoSet.accessors;
            int uid = processAccessors.uid.getInt(processInfo);
            if (uid < 0) return unresolved(OsenseProcessIdentityFailure.INVALID_UID);

            int pid = processAccessors.pid.getInt(processInfo);
            if (pid <= 0) return unresolved(OsenseProcessIdentityFailure.INVALID_PID);

            Object processNameValue = processAccessors.processName.invoke(processInfo);
            if (!(processNameValue instanceof String)
                    || !isNonBlank((String) processNameValue)) {
                return unresolved(OsenseProcessIdentityFailure.INVALID_PROCESS_NAME);
            }

            Object group = processAccessors.group.get(processInfo);
            Object processRecord = processAccessors.processRecord.get(processInfo);
            if (group == null || processRecord == null) {
                return unresolved(OsenseProcessIdentityFailure.INCOMPLETE_PACKAGE_IDENTITY);
            }

            AccessorSet<GroupAccessors> groupSet = GROUP_ACCESSORS.get(group.getClass());
            if (!groupSet.isReady()) return unresolved(groupSet.failure);
            Object groupPackageValue = groupSet.accessors.packageName.get(group);

            AccessorSet<ProcessRecordAccessors> processRecordSet =
                    PROCESS_RECORD_ACCESSORS.get(processRecord.getClass());
            if (!processRecordSet.isReady()) return unresolved(processRecordSet.failure);
            Object applicationInfo = processRecordSet.accessors.applicationInfo.get(processRecord);
            if (applicationInfo == null) {
                return unresolved(OsenseProcessIdentityFailure.INCOMPLETE_PACKAGE_IDENTITY);
            }

            AccessorSet<ApplicationInfoAccessors> applicationInfoSet =
                    APPLICATION_INFO_ACCESSORS.get(applicationInfo.getClass());
            if (!applicationInfoSet.isReady()) return unresolved(applicationInfoSet.failure);
            Object recordPackageValue =
                    applicationInfoSet.accessors.packageName.get(applicationInfo);

            if (!(groupPackageValue instanceof String)
                    || !(recordPackageValue instanceof String)
                    || !isNonBlank((String) groupPackageValue)
                    || !isNonBlank((String) recordPackageValue)) {
                return unresolved(OsenseProcessIdentityFailure.INCOMPLETE_PACKAGE_IDENTITY);
            }

            String groupPackageName = (String) groupPackageValue;
            String recordPackageName = (String) recordPackageValue;
            if (!groupPackageName.equals(recordPackageName)) {
                return unresolved(OsenseProcessIdentityFailure.PACKAGE_NAME_CONFLICT);
            }

            return OsenseProcessIdentityResolution.resolved(new OsenseProcessIdentity(
                    uid, pid, (String) processNameValue, groupPackageName));
        } catch (ReflectiveOperationException | RuntimeException error) {
            return unresolved(OsenseProcessIdentityFailure.REFLECTION_FAILED);
        }
    }

    private static boolean isNonBlank(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) return true;
        }
        return false;
    }

    private static OsenseProcessIdentityResolution unresolved(
            OsenseProcessIdentityFailure failure) {
        return OsenseProcessIdentityResolution.unresolved(failure);
    }

    private static <T> AccessorSet<T> load(Class<?> type, AccessorFactory<T> factory) {
        try {
            return AccessorSet.ready(factory.create(type));
        } catch (NoSuchFieldException | NoSuchMethodException missing) {
            return AccessorSet.failed(OsenseProcessIdentityFailure.ACCESSOR_MISSING);
        } catch (RuntimeException reflectionFailure) {
            return AccessorSet.failed(OsenseProcessIdentityFailure.REFLECTION_FAILED);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Continue through the hierarchy; OSense model fields may be inherited.
            }
        }
        throw new NoSuchFieldException(type.getName() + '#' + name);
    }

    private static Method findMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Continue through the hierarchy; OSense model methods may be inherited.
            }
        }
        throw new NoSuchMethodException(type.getName() + '#' + name + "()");
    }

    private interface AccessorFactory<T> {
        T create(Class<?> type) throws NoSuchFieldException, NoSuchMethodException;
    }

    private static final class AccessorCache<T> {
        private final ConcurrentMap<Class<?>, AccessorSet<T>> values =
                new ConcurrentHashMap<>();
        private final AccessorFactory<T> factory;

        private AccessorCache(AccessorFactory<T> factory) {
            this.factory = factory;
        }

        AccessorSet<T> get(Class<?> type) {
            AccessorSet<T> cached = values.get(type);
            if (cached != null) return cached;
            for (Class<?> current = type.getSuperclass();
                    current != null;
                    current = current.getSuperclass()) {
                AccessorSet<T> inherited = values.get(current);
                if (inherited != null && inherited.isReady()) {
                    AccessorSet<T> raced = values.putIfAbsent(type, inherited);
                    return raced != null ? raced : inherited;
                }
            }
            AccessorSet<T> loaded = load(type, factory);
            AccessorSet<T> raced = values.putIfAbsent(type, loaded);
            return raced != null ? raced : loaded;
        }
    }

    private static final class AccessorSet<T> {
        private final T accessors;
        private final OsenseProcessIdentityFailure failure;

        private AccessorSet(T accessors, OsenseProcessIdentityFailure failure) {
            this.accessors = accessors;
            this.failure = failure;
        }

        static <T> AccessorSet<T> ready(T accessors) {
            return new AccessorSet<>(accessors, null);
        }

        static <T> AccessorSet<T> failed(OsenseProcessIdentityFailure failure) {
            return new AccessorSet<>(null, failure);
        }

        boolean isReady() {
            return accessors != null;
        }
    }

    private static final class ProcessInfoAccessors {
        private final Field uid;
        private final Field pid;
        private final Method processName;
        private final Field group;
        private final Field processRecord;

        private ProcessInfoAccessors(
                Field uid, Field pid, Method processName, Field group, Field processRecord) {
            this.uid = uid;
            this.pid = pid;
            this.processName = processName;
            this.group = group;
            this.processRecord = processRecord;
        }

        static ProcessInfoAccessors create(Class<?> type)
                throws NoSuchFieldException, NoSuchMethodException {
            return new ProcessInfoAccessors(
                    findField(type, "mUid"),
                    findField(type, "mPid"),
                    findMethod(type, "getProcessName"),
                    findField(type, "mGroup"),
                    findField(type, "mToBeKill"));
        }
    }

    private static final class GroupAccessors {
        private final Field packageName;

        private GroupAccessors(Field packageName) {
            this.packageName = packageName;
        }

        static GroupAccessors create(Class<?> type) throws NoSuchFieldException {
            return new GroupAccessors(findField(type, "mPackageName"));
        }
    }

    private static final class ProcessRecordAccessors {
        private final Field applicationInfo;

        private ProcessRecordAccessors(Field applicationInfo) {
            this.applicationInfo = applicationInfo;
        }

        static ProcessRecordAccessors create(Class<?> type) throws NoSuchFieldException {
            return new ProcessRecordAccessors(findField(type, "info"));
        }
    }

    private static final class ApplicationInfoAccessors {
        private final Field packageName;

        private ApplicationInfoAccessors(Field packageName) {
            this.packageName = packageName;
        }

        static ApplicationInfoAccessors create(Class<?> type) throws NoSuchFieldException {
            return new ApplicationInfoAccessors(findField(type, "packageName"));
        }
    }
}
