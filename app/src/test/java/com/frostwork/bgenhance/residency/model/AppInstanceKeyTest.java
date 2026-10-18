package com.frostwork.bgenhance.residency.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AppInstanceKeyTest {
    private static final String PACKAGE_NAME = "com.example.reader";

    @Test
    public void rejectsEmptyPackageSeparatorAndUnrepresentableUser() {
        assertThrows(IllegalArgumentException.class, () -> new AppInstanceKey("", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AppInstanceKey(PACKAGE_NAME + "\u0000clone", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AppInstanceKey(PACKAGE_NAME, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new AppInstanceKey(PACKAGE_NAME, AppInstanceKey.MAX_USER_ID + 1));
        assertThrows(NullPointerException.class, () -> new AppInstanceKey(null, 0));
    }

    @Test
    public void encodeAndDecodeRoundTripWithoutCollidingWithThePrimaryPackage() {
        AppInstanceKey owner = new AppInstanceKey(PACKAGE_NAME, AppResidencyPolicy.PRIMARY_USER_ID);
        AppInstanceKey clone = new AppInstanceKey(PACKAGE_NAME, 999);

        assertEquals(PACKAGE_NAME, owner.encode());
        assertEquals(owner, AppInstanceKey.decode(owner.encode()));
        assertEquals(clone, AppInstanceKey.decode(clone.encode()));
        assertTrue(owner.compareTo(clone) < 0);
        assertTrue(clone.encode().indexOf('\u0000') > 0);
        assertEquals(clone, AppInstanceKey.fromUid(PACKAGE_NAME, 999 * AppInstanceKey.PER_USER_RANGE + 10_201));
        assertThrows(IllegalArgumentException.class, () -> AppInstanceKey.fromUid(PACKAGE_NAME, -1));
        assertThrows(IllegalArgumentException.class,
                () -> AppInstanceKey.decode(PACKAGE_NAME + "\u0000" + "0"));
    }

    @Test
    public void ofCopiesOnlyPackageAndUserFromThePolicy() {
        AppResidencyPolicy policy = ResidencyPresets.create(PACKAGE_NAME, 999, ResidencyMode.BALANCED);

        assertEquals(new AppInstanceKey(PACKAGE_NAME, 999), AppInstanceKey.of(policy));
        assertEquals(policy.instanceKey(), AppInstanceKey.of(policy));
        assertEquals(AppResidencyPolicy.PRIMARY_USER_ID,
                ResidencyPresets.create(PACKAGE_NAME, ResidencyMode.BALANCED).userId());
    }
}
