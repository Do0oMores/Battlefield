package top.mores.battlefield.game.medical;

/** Hardware-release latch, independent of medical eligibility, animation and stock. */
public final class FireReleaseGate {
    private volatile boolean waiting;
    public void started() { waiting = true; }
    public void observe(boolean physicalFireDown) { if (!physicalFireDown) waiting = false; }
    public boolean blocked(boolean medicalActive) { return medicalActive || waiting; }
    public void disconnect() { waiting = false; }
}
