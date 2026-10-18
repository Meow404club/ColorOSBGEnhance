package com.frostwork.bgenhance.residency.model;

import java.util.Objects;

/** One installed package in one Android user. */
public final class AppInstanceKey implements Comparable<AppInstanceKey> {
    public static final int PER_USER_RANGE = 100_000;
    /** Inclusive upper bound. A larger id cannot select an Android user inside a signed UID. */
    public static final int MAX_USER_ID = Integer.MAX_VALUE / PER_USER_RANGE;
    private static final char IDENTITY_SEPARATOR = '\u0000';

    private final String packageName;
    private final int userId;

    public AppInstanceKey(String packageName, int userId) {
        this.packageName = Objects.requireNonNull(packageName, "packageName");
        if (packageName.isEmpty() || packageName.indexOf(IDENTITY_SEPARATOR) >= 0) {
            throw new IllegalArgumentException(
                    "packageName must not be empty or contain NUL");
        }
        if (!isRepresentableUserId(userId)) {
            throw new IllegalArgumentException(
                    "userId cannot form a non-negative UID: " + userId);
        }
        this.userId = userId;
    }

    public static boolean isRepresentableUserId(int userId) {
        return userId >= 0 && userId <= MAX_USER_ID;
    }

    /**
     * Derives the user from a full UID. A negative UID is rejected and is not treated as user 0.
     */
    public static AppInstanceKey fromUid(String packageName, int uid) {
        if (uid < 0) {
            throw new IllegalArgumentException("uid < 0");
        }
        return new AppInstanceKey(packageName, uid / PER_USER_RANGE);
    }

    public static AppInstanceKey of(AppResidencyPolicy policy) {
        AppResidencyPolicy source = Objects.requireNonNull(policy, "policy");
        return new AppInstanceKey(source.packageName(), source.userId());
    }

    public String packageName() {
        return packageName;
    }

    public int userId() {
        return userId;
    }

    /**
     * Stable text form. User 0 is the package name alone. Every other user appends NUL and the
     * decimal user id, which a package name cannot contain.
     */
    public String encode() {
        if (userId == 0) {
            return packageName;
        }
        return packageName + IDENTITY_SEPARATOR + userId;
    }

    public static AppInstanceKey decode(String encoded) {
        String text = Objects.requireNonNull(encoded, "encoded");
        int separator = text.indexOf(IDENTITY_SEPARATOR);
        if (separator < 0) {
            return new AppInstanceKey(text, 0);
        }
        String packageName = text.substring(0, separator);
        String userText = text.substring(separator + 1);
        if (userText.isEmpty() || userText.charAt(0) == '-' || userText.charAt(0) == '+') {
            throw new IllegalArgumentException("invalid instance identity");
        }
        int userId;
        try {
            userId = Integer.parseInt(userText);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid instance identity");
        }
        if (userId == 0) {
            throw new IllegalArgumentException("invalid instance identity");
        }
        return new AppInstanceKey(packageName, userId);
    }

    @Override
    public int compareTo(AppInstanceKey other) {
        int byPackage = packageName.compareTo(other.packageName);
        if (byPackage != 0) {
            return byPackage;
        }
        return Integer.compare(userId, other.userId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AppInstanceKey)) {
            return false;
        }
        AppInstanceKey that = (AppInstanceKey) other;
        return userId == that.userId && packageName.equals(that.packageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packageName, userId);
    }
}
