package com.frostwork.bgenhance.testing;

/**
 * Synthetic identities shared by residency unit tests.
 *
 * <p>New fixtures must use the reserved {@code com.example} namespace and invented numeric
 * identities. Device identifiers, developer identifiers, and real application package names do
 * not belong in tests.</p>
 */
public final class ResidencyTestFixtures {
    public static final String MANAGED_PACKAGE = "com.example.residency.managed";
    public static final int MANAGED_UID = 10_123;
    public static final int MANAGED_PID = 20_123;

    public static final String SYSTEM_MANAGED_PACKAGE = "com.example.residency.systemmanaged";
    public static final int SYSTEM_MANAGED_UID = 10_124;
    public static final int SYSTEM_MANAGED_PID = 20_124;

    public static final String UNMANAGED_PACKAGE = "com.example.residency.unmanaged";
    public static final int UNMANAGED_UID = 10_125;
    public static final int UNMANAGED_PID = 20_125;

    private ResidencyTestFixtures() {
    }
}
