package dev.inferdroid.engine;

/** Main-thread admission shared by independent chat and speech lifecycles. */
public final class WorkGate {
    private final java.util.Set<Object> owners = new java.util.HashSet<>();
    public boolean isBusy() { return !owners.isEmpty(); }
    public boolean acquire(Object caller) {
        if (isBusy()) return false;
        owners.add(caller);
        return true;
    }
    /** Independent engines can drain/unload together; new work waits for both. */
    public void hold(Object caller) { owners.add(caller); }
    public void release(Object caller) { owners.remove(caller); }
}
