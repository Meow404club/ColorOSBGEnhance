package e4;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Descriptor-only fixture for the three MemoryGuard terminal methods. */
public class t {
    private final b4.v0 d;

    public t(int code) {
        this.d = new b4.v0(code);
    }

    public void setCode(int code) {
        this.d.setCode(code);
    }

    private void T0(a0 process, y state) {}

    private void S0(z group, y state, d3.a stopState) {}

    private List<?> m1(
            List<?> processes, Map<?, ?> sortRules, y state, Map<?, ?> memory) {
        return new ArrayList<>(processes);
    }

    private List<?> I(
            List<?> processes,
            Map<?, ?> sortRules,
            Map<?, ?> memory,
            y state,
            boolean firstPass,
            boolean secondPass) {
        return m1(processes, sortRules, state, memory);
    }

    private void U0(List<?> processes, y state, Map<?, ?> memory) {
        T0(null, state);
        S0(null, state, null);
    }

    private static void T0(a0 process, Object state) {}

    private boolean S0(z group, y state, Object stopState) {
        return false;
    }

    private ArrayList<?> m1(
            ArrayList<?> processes, Map<?, ?> sortRules, y state, Map<?, ?> memory) {
        return new ArrayList<>(processes);
    }

    private static ArrayList<?> I(
            ArrayList<?> processes,
            Map<?, ?> sortRules,
            Map<?, ?> memory,
            y state,
            boolean firstPass,
            boolean secondPass) {
        return processes;
    }

    public void U0(ArrayList<?> processes, y state, Map<?, ?> memory) {}
}
