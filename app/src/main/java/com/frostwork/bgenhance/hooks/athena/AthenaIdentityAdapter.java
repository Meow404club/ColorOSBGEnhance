package com.frostwork.bgenhance.hooks.athena;

import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Preheated, in-memory-only identity access for Athena terminal callbacks. */
public final class AthenaIdentityAdapter {
    private static final int PER_USER_RANGE = 100_000;

    private AthenaIdentityAdapter() {}

    public static Preparation prepare(AthenaTerminalRuntime.CapabilityMatrix matrix) {
        if (matrix == null || !matrix.allAvailable()) {
            return Preparation.failed(PreparationStatus.MATRIX_UNAVAILABLE);
        }
        try {
            Method periodicSingle = required(
                    matrix, AthenaTerminalRuntime.Capability.PERIODIC_SINGLE);
            Method tournamentSingle = required(
                    matrix, AthenaTerminalRuntime.Capability.TOURNAMENT_SINGLE);
            Method memorySingle = required(
                    matrix, AthenaTerminalRuntime.Capability.MEMORY_SINGLE);
            Method memoryGroup = required(
                    matrix, AthenaTerminalRuntime.Capability.MEMORY_GROUP);
            Method memoryPrerelease = required(
                    matrix, AthenaTerminalRuntime.Capability.MEMORY_PRERELEASE);

            Class<?> processType = periodicSingle.getParameterTypes()[2];
            if (tournamentSingle.getParameterTypes()[0] != processType) {
                return Preparation.failed(PreparationStatus.MODEL_MISMATCH);
            }

            Class<?> processCandidateType = memorySingle.getParameterTypes()[0];
            Class<?> memoryStateType = memorySingle.getParameterTypes()[1];
            Class<?> groupType = memoryGroup.getParameterTypes()[0];
            Class<?> memoryOwnerType = memorySingle.getDeclaringClass();
            if (memoryGroup.getDeclaringClass() != memoryOwnerType
                    || memoryPrerelease.getDeclaringClass() != memoryOwnerType
                    || memoryGroup.getParameterTypes()[1] != memoryStateType
                    || memoryPrerelease.getParameterTypes()[2] != memoryStateType) {
                return Preparation.failed(PreparationStatus.MODEL_MISMATCH);
            }

            ProcessAccessors process = ProcessAccessors.create(processType);
            CandidateAccessors candidate = CandidateAccessors.create(
                    processCandidateType, processType);
            GroupAccessors group = GroupAccessors.create(groupType);
            OwnerAccessors owner = OwnerAccessors.create(memoryOwnerType);
            return Preparation.ready(new Prepared(
                    processType,
                    processCandidateType,
                    groupType,
                    memoryOwnerType,
                    process,
                    candidate,
                    group,
                    owner));
        } catch (NoSuchFieldException | NoSuchMethodException error) {
            return Preparation.failed(PreparationStatus.ACCESSOR_MISSING);
        } catch (InvalidAccessorException error) {
            return Preparation.failed(PreparationStatus.INVALID_ACCESSOR);
        } catch (LinkageError | RuntimeException error) {
            return Preparation.failed(PreparationStatus.LOOKUP_FAILED);
        }
    }

    private static Method required(
            AthenaTerminalRuntime.CapabilityMatrix matrix,
            AthenaTerminalRuntime.Capability capability) {
        Method method = matrix.methodOrNull(capability);
        if (method == null || !AthenaTerminalRuntime.matches(capability, method)) {
            throw new IllegalStateException("missing validated capability " + capability);
        }
        return method;
    }

    public enum PreparationStatus {
        READY,
        MATRIX_UNAVAILABLE,
        MODEL_MISMATCH,
        ACCESSOR_MISSING,
        INVALID_ACCESSOR,
        LOOKUP_FAILED
    }

    public enum Failure {
        NULL_VALUE,
        WRONG_RUNTIME_TYPE,
        INVALID_PACKAGE,
        INVALID_UID,
        USER_ID_CONFLICT,
        OBJECT_CONFLICT,
        EMPTY_GROUP,
        INVALID_GROUP,
        MULTIPLE_UIDS,
        INVALID_ACTION_OWNER,
        INVALID_ACTION_CODE,
        REFLECTION_FAILED
    }

    public static final class Preparation {
        private final PreparationStatus status;
        private final Prepared prepared;

        private Preparation(PreparationStatus status, Prepared prepared) {
            this.status = Objects.requireNonNull(status, "status");
            this.prepared = prepared;
        }

        private static Preparation ready(Prepared prepared) {
            return new Preparation(
                    PreparationStatus.READY,
                    Objects.requireNonNull(prepared, "prepared"));
        }

        private static Preparation failed(PreparationStatus status) {
            if (status == PreparationStatus.READY) {
                throw new IllegalArgumentException("READY requires prepared accessors");
            }
            return new Preparation(status, null);
        }

        public PreparationStatus status() { return status; }
        public boolean isReady() { return prepared != null; }
        public Prepared preparedOrNull() { return prepared; }
    }

    public static final class Prepared {
        private final Class<?> processType;
        private final Class<?> candidateType;
        private final Class<?> groupType;
        private final Class<?> ownerType;
        private final ProcessAccessors process;
        private final CandidateAccessors candidate;
        private final GroupAccessors group;
        private final OwnerAccessors owner;

        private Prepared(
                Class<?> processType,
                Class<?> candidateType,
                Class<?> groupType,
                Class<?> ownerType,
                ProcessAccessors process,
                CandidateAccessors candidate,
                GroupAccessors group,
                OwnerAccessors owner) {
            this.processType = processType;
            this.candidateType = candidateType;
            this.groupType = groupType;
            this.ownerType = ownerType;
            this.process = process;
            this.candidate = candidate;
            this.group = group;
            this.owner = owner;
        }

        public IdentityResolution resolveProcess(Object value) {
            if (value == null) return IdentityResolution.failed(Failure.NULL_VALUE);
            if (!processType.isInstance(value)) {
                return IdentityResolution.failed(Failure.WRONG_RUNTIME_TYPE);
            }
            try {
                String packageName = (String) process.packageName.get(value);
                if (!isNonBlank(packageName)) {
                    return IdentityResolution.failed(Failure.INVALID_PACKAGE);
                }
                int uid = process.uid.getInt(value);
                if (uid < 0) return IdentityResolution.failed(Failure.INVALID_UID);
                int userId = process.userId.getInt(value);
                if (!matchesUser(uid, userId)) {
                    return IdentityResolution.failed(Failure.USER_ID_CONFLICT);
                }
                int rawPid = process.pid.getInt(value);
                int pid = rawPid > 0 ? rawPid : ResidencyTarget.UNKNOWN_PID;
                String processName = (String) process.processName.get(value);
                return IdentityResolution.resolved(new Identity(
                        packageName,
                        uid,
                        pid,
                        processName == null ? "" : processName));
            } catch (ReflectiveOperationException | RuntimeException error) {
                return IdentityResolution.failed(Failure.REFLECTION_FAILED);
            }
        }

        public IdentityResolution resolveCandidate(Object value) {
            if (value == null) return IdentityResolution.failed(Failure.NULL_VALUE);
            if (!candidateType.isInstance(value)) {
                return IdentityResolution.failed(Failure.WRONG_RUNTIME_TYPE);
            }
            try {
                Object processValue = candidate.process.invoke(value);
                IdentityResolution processResolution = resolveProcess(processValue);
                Identity processIdentity = processResolution.identityOrNull();
                if (processIdentity == null) return processResolution;

                Object packageValue = candidate.packageName.invoke(value);
                Object processNameValue = candidate.processName.invoke(value);
                Object pidValue = candidate.pid.invoke(value);
                Object uidValue = candidate.uid.invoke(value);
                Object userIdValue = candidate.userId.invoke(value);
                if (!(packageValue instanceof String)
                        || !(processNameValue instanceof String)
                        || !(pidValue instanceof Integer)
                        || !(uidValue instanceof Integer)
                        || !(userIdValue instanceof Integer)) {
                    return IdentityResolution.failed(Failure.OBJECT_CONFLICT);
                }

                int uid = ((Integer) uidValue).intValue();
                int rawPid = ((Integer) pidValue).intValue();
                int pid = rawPid > 0 ? rawPid : ResidencyTarget.UNKNOWN_PID;
                int userId = ((Integer) userIdValue).intValue();
                if (!processIdentity.packageName.equals(packageValue)
                        || processIdentity.uid != uid
                        || processIdentity.pid != pid
                        || !processIdentity.processName.equals(processNameValue)
                        || !matchesUser(uid, userId)) {
                    return IdentityResolution.failed(Failure.OBJECT_CONFLICT);
                }
                return IdentityResolution.resolved(processIdentity);
            } catch (ReflectiveOperationException | RuntimeException error) {
                return IdentityResolution.failed(Failure.REFLECTION_FAILED);
            }
        }

        public IdentityResolution resolveGroup(Object value) {
            if (value == null) return IdentityResolution.failed(Failure.NULL_VALUE);
            if (!groupType.isInstance(value)) {
                return IdentityResolution.failed(Failure.WRONG_RUNTIME_TYPE);
            }
            try {
                Object packageValue = group.packageName.invoke(value);
                Object userIdValue = group.userId.invoke(value);
                Object groupsValue = group.candidates.invoke(value);
                if (!(packageValue instanceof String)
                        || !isNonBlank((String) packageValue)
                        || !(userIdValue instanceof Integer)
                        || !(groupsValue instanceof Map)) {
                    return IdentityResolution.failed(Failure.INVALID_GROUP);
                }

                String packageName = (String) packageValue;
                int userId = ((Integer) userIdValue).intValue();
                Map<?, ?> groups = (Map<?, ?>) groupsValue;
                if (groups.isEmpty()) return IdentityResolution.failed(Failure.EMPTY_GROUP);

                Identity resolved = null;
                Set<Integer> uids = new HashSet<>();
                for (Map.Entry<?, ?> entry : groups.entrySet()) {
                    if (!(entry.getKey() instanceof Integer)
                            || !(entry.getValue() instanceof List)
                            || ((List<?>) entry.getValue()).isEmpty()) {
                        return IdentityResolution.failed(Failure.INVALID_GROUP);
                    }
                    int mapUid = ((Integer) entry.getKey()).intValue();
                    if (mapUid < 0) return IdentityResolution.failed(Failure.INVALID_UID);
                    uids.add(mapUid);
                    for (Object candidateValue : (List<?>) entry.getValue()) {
                        IdentityResolution memberResolution = resolveCandidate(candidateValue);
                        Identity member = memberResolution.identityOrNull();
                        if (member == null) {
                            return IdentityResolution.failed(Failure.INVALID_GROUP);
                        }
                        if (member.uid != mapUid
                                || !packageName.equals(member.packageName)
                                || !matchesUser(member.uid, userId)) {
                            return IdentityResolution.failed(Failure.OBJECT_CONFLICT);
                        }
                        if (resolved == null) resolved = member;
                    }
                }
                if (resolved == null) return IdentityResolution.failed(Failure.EMPTY_GROUP);
                if (uids.size() != 1) {
                    return IdentityResolution.failed(Failure.MULTIPLE_UIDS);
                }
                return IdentityResolution.resolved(new Identity(
                        packageName,
                        resolved.uid,
                        ResidencyTarget.UNKNOWN_PID,
                        ""));
            } catch (ReflectiveOperationException | RuntimeException error) {
                return IdentityResolution.failed(Failure.REFLECTION_FAILED);
            }
        }

        public ActionCodeResolution resolveActionCode(Object value) {
            if (value == null || !ownerType.isInstance(value)) {
                return ActionCodeResolution.failed(Failure.INVALID_ACTION_OWNER);
            }
            try {
                Object action = owner.action.get(value);
                if (action == null || !owner.actionType.isInstance(action)) {
                    return ActionCodeResolution.failed(Failure.INVALID_ACTION_OWNER);
                }
                Object codeValue = owner.actionCode.invoke(action);
                if (!(codeValue instanceof Integer)
                        || ((Integer) codeValue).intValue() <= 0) {
                    return ActionCodeResolution.failed(Failure.INVALID_ACTION_CODE);
                }
                return ActionCodeResolution.resolved(((Integer) codeValue).intValue());
            } catch (ReflectiveOperationException | RuntimeException error) {
                return ActionCodeResolution.failed(Failure.REFLECTION_FAILED);
            }
        }
    }

    public static final class Identity {
        private final String packageName;
        private final int uid;
        private final int pid;
        private final String processName;

        private Identity(String packageName, int uid, int pid, String processName) {
            this.packageName = packageName;
            this.uid = uid;
            this.pid = pid;
            this.processName = processName;
        }

        public String packageName() { return packageName; }
        public int uid() { return uid; }
        public int pid() { return pid; }
        public String processName() { return processName; }

        public ResidencyTarget target() {
            return new ResidencyTarget(packageName, uid, pid);
        }
    }

    public static final class IdentityResolution {
        private final Identity identity;
        private final Failure failure;

        private IdentityResolution(Identity identity, Failure failure) {
            this.identity = identity;
            this.failure = failure;
        }

        private static IdentityResolution resolved(Identity identity) {
            return new IdentityResolution(Objects.requireNonNull(identity, "identity"), null);
        }

        private static IdentityResolution failed(Failure failure) {
            return new IdentityResolution(null, Objects.requireNonNull(failure, "failure"));
        }

        public boolean isResolved() { return identity != null; }
        public Identity identityOrNull() { return identity; }
        public Failure failureOrNull() { return failure; }
    }

    public static final class ActionCodeResolution {
        private final int code;
        private final Failure failure;

        private ActionCodeResolution(int code, Failure failure) {
            this.code = code;
            this.failure = failure;
        }

        private static ActionCodeResolution resolved(int code) {
            return new ActionCodeResolution(code, null);
        }

        private static ActionCodeResolution failed(Failure failure) {
            return new ActionCodeResolution(-1, Objects.requireNonNull(failure, "failure"));
        }

        public boolean isResolved() { return failure == null; }
        public int code() { return code; }
        public Failure failureOrNull() { return failure; }
    }

    private static final class ProcessAccessors {
        private final Field packageName;
        private final Field processName;
        private final Field uid;
        private final Field pid;
        private final Field userId;

        private ProcessAccessors(
                Field packageName, Field processName, Field uid, Field pid, Field userId) {
            this.packageName = packageName;
            this.processName = processName;
            this.uid = uid;
            this.pid = pid;
            this.userId = userId;
        }

        private static ProcessAccessors create(Class<?> type)
                throws NoSuchFieldException, InvalidAccessorException {
            return new ProcessAccessors(
                    exactField(type, "pkgName", String.class, Modifier.PUBLIC),
                    exactField(type, "procName", String.class, Modifier.PUBLIC),
                    exactField(type, "uid", int.class, Modifier.PUBLIC),
                    exactField(type, "pid", int.class, Modifier.PUBLIC),
                    exactField(type, "userId", int.class, Modifier.PUBLIC));
        }
    }

    private static final class CandidateAccessors {
        private final Method packageName;
        private final Method processName;
        private final Method pid;
        private final Method uid;
        private final Method userId;
        private final Method process;

        private CandidateAccessors(
                Method packageName,
                Method processName,
                Method pid,
                Method uid,
                Method userId,
                Method process) {
            this.packageName = packageName;
            this.processName = processName;
            this.pid = pid;
            this.uid = uid;
            this.userId = userId;
            this.process = process;
        }

        private static CandidateAccessors create(Class<?> type, Class<?> processType)
                throws NoSuchMethodException, InvalidAccessorException {
            return new CandidateAccessors(
                    exactMethod(type, "h", String.class, Modifier.PUBLIC),
                    exactMethod(type, "k", String.class, Modifier.PUBLIC),
                    exactMethod(type, "i", int.class, Modifier.PUBLIC),
                    exactMethod(type, "p", int.class, Modifier.PUBLIC),
                    exactMethod(type, "q", int.class, Modifier.PUBLIC),
                    exactMethod(type, "j", processType, Modifier.PUBLIC));
        }
    }

    private static final class GroupAccessors {
        private final Method packageName;
        private final Method userId;
        private final Method candidates;

        private GroupAccessors(Method packageName, Method userId, Method candidates) {
            this.packageName = packageName;
            this.userId = userId;
            this.candidates = candidates;
        }

        private static GroupAccessors create(Class<?> type)
                throws NoSuchMethodException, InvalidAccessorException {
            return new GroupAccessors(
                    exactMethod(type, "n", String.class, Modifier.PUBLIC),
                    exactMethod(type, "y", int.class, Modifier.PUBLIC),
                    exactMethod(type, "q", Map.class, Modifier.PUBLIC));
        }
    }

    private static final class OwnerAccessors {
        private final Field action;
        private final Class<?> actionType;
        private final Method actionCode;

        private OwnerAccessors(Field action, Class<?> actionType, Method actionCode) {
            this.action = action;
            this.actionType = actionType;
            this.actionCode = actionCode;
        }

        private static OwnerAccessors create(Class<?> ownerType)
                throws NoSuchFieldException, NoSuchMethodException, InvalidAccessorException {
            Field action = ownerType.getDeclaredField("d");
            Class<?> actionType = action.getType();
            if (action.getDeclaringClass() != ownerType
                    || action.getModifiers() != (Modifier.PRIVATE | Modifier.FINAL)
                    || !"b4.v0".equals(actionType.getName())) {
                throw new InvalidAccessorException();
            }
            action.setAccessible(true);

            Method actionCode = actionType.getMethod("D");
            if (!"b4.l".equals(actionCode.getDeclaringClass().getName())
                    || actionCode.getModifiers() != Modifier.PUBLIC
                    || actionCode.getReturnType() != int.class
                    || actionCode.getParameterTypes().length != 0) {
                throw new InvalidAccessorException();
            }
            actionCode.setAccessible(true);
            return new OwnerAccessors(action, actionType, actionCode);
        }
    }

    private static Field exactField(
            Class<?> owner, String name, Class<?> type, int modifiers)
            throws NoSuchFieldException, InvalidAccessorException {
        Field field = owner.getDeclaredField(name);
        if (field.getDeclaringClass() != owner
                || field.getType() != type
                || field.getModifiers() != modifiers) {
            throw new InvalidAccessorException();
        }
        field.setAccessible(true);
        return field;
    }

    private static Method exactMethod(
            Class<?> owner, String name, Class<?> returnType, int modifiers, Class<?>... parameters)
            throws NoSuchMethodException, InvalidAccessorException {
        Method method = owner.getDeclaredMethod(name, parameters);
        if (method.getDeclaringClass() != owner
                || method.getReturnType() != returnType
                || method.getModifiers() != modifiers) {
            throw new InvalidAccessorException();
        }
        method.setAccessible(true);
        return method;
    }

    private static boolean isNonBlank(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) return true;
        }
        return false;
    }

    private static boolean matchesUser(int uid, int userId) {
        return uid >= 0 && (userId < 0 || uid / PER_USER_RANGE == userId);
    }

    private static final class InvalidAccessorException extends Exception {}
}
