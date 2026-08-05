package com.frostwork.bgenhance.residency.legacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LegacyHansPolicyDocument {
    final int version;
    final boolean enabled;
    final long revision;
    final List<LegacyHansRule> rules;

    LegacyHansPolicyDocument(
            int version, boolean enabled, long revision, List<LegacyHansRule> rules) {
        this.version = version;
        this.enabled = enabled;
        this.revision = revision;
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
    }
}
