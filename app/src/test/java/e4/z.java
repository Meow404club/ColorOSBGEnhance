package e4;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal package group with the verified Athena getter ABI. */
public class z {
    private final String packageName;
    private final int userId;
    private final Map<Integer, List<a0>> candidates = new LinkedHashMap<>();

    public z(String packageName, int userId) {
        this.packageName = packageName;
        this.userId = userId;
    }

    public z add(a0 candidate) {
        candidates.computeIfAbsent(candidate.p(), ignored -> new ArrayList<>())
                .add(candidate);
        return this;
    }

    public String n() { return packageName; }
    public int y() { return userId; }
    public Map q() { return candidates; }
}
