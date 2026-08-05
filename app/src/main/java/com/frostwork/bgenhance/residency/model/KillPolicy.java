package com.frostwork.bgenhance.residency.model;

import java.util.Objects;

/** Per-application protection for known vendor process termination paths. */
public final class KillPolicy {
    private final boolean protectOguardKill;
    private final boolean protectOguardForceStop;
    private final boolean protectAthenaPeriodic;
    private final boolean protectFastClear;
    private final boolean protectTournament;
    private final boolean protectMemoryGuard;
    private final boolean protectNirvana;
    private final boolean protectOsensePerProcess;

    public KillPolicy(
            boolean protectOguardKill,
            boolean protectOguardForceStop,
            boolean protectAthenaPeriodic,
            boolean protectFastClear,
            boolean protectTournament,
            boolean protectMemoryGuard,
            boolean protectNirvana,
            boolean protectOsensePerProcess) {
        this.protectOguardKill = protectOguardKill;
        this.protectOguardForceStop = protectOguardForceStop;
        this.protectAthenaPeriodic = protectAthenaPeriodic;
        this.protectFastClear = protectFastClear;
        this.protectTournament = protectTournament;
        this.protectMemoryGuard = protectMemoryGuard;
        this.protectNirvana = protectNirvana;
        this.protectOsensePerProcess = protectOsensePerProcess;
    }

    public static KillPolicy none() {
        return new KillPolicy(false, false, false, false, false, false, false, false);
    }

    public static KillPolicy all() {
        return new KillPolicy(true, true, true, true, true, true, true, true);
    }

    public boolean protectOguardKill() { return protectOguardKill; }
    public boolean protectOguardForceStop() { return protectOguardForceStop; }
    public boolean protectAthenaPeriodic() { return protectAthenaPeriodic; }
    public boolean protectFastClear() { return protectFastClear; }
    public boolean protectTournament() { return protectTournament; }
    public boolean protectMemoryGuard() { return protectMemoryGuard; }
    public boolean protectNirvana() { return protectNirvana; }
    public boolean protectOsensePerProcess() { return protectOsensePerProcess; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof KillPolicy)) return false;
        KillPolicy that = (KillPolicy) other;
        return protectOguardKill == that.protectOguardKill
                && protectOguardForceStop == that.protectOguardForceStop
                && protectAthenaPeriodic == that.protectAthenaPeriodic
                && protectFastClear == that.protectFastClear
                && protectTournament == that.protectTournament
                && protectMemoryGuard == that.protectMemoryGuard
                && protectNirvana == that.protectNirvana
                && protectOsensePerProcess == that.protectOsensePerProcess;
    }

    @Override
    public int hashCode() {
        return Objects.hash(protectOguardKill, protectOguardForceStop, protectAthenaPeriodic,
                protectFastClear, protectTournament, protectMemoryGuard, protectNirvana,
                protectOsensePerProcess);
    }
}
