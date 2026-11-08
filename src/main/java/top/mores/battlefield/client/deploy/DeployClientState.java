package top.mores.battlefield.client.deploy;

import top.mores.battlefield.protocol.SoldierClass;

/** Client preference only; server remains authoritative for current combat class. */
public final class DeployClientState {
    private static SoldierClass soldierClass = SoldierClass.ASSAULT;
    private static String optionId = "";
    private DeployClientState() {}
    public static SoldierClass soldierClass() { return soldierClass; }
    public static void soldierClass(SoldierClass type) { soldierClass = type; }
    public static String optionId() { return optionId; }
    public static void optionId(String value) { optionId = value == null ? "" : value; }
    public static void reset() { soldierClass = SoldierClass.ASSAULT; optionId = ""; }
}