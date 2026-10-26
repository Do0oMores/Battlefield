package top.mores.battlefield.loadout;

/** Loaded + reserve always respects the same total cap as Battlefield's existing ammo station. */
public record DeploymentAmmoPolicy(int loaded, int reserve) {
    public static DeploymentAmmoPolicy plan(int magazineCapacity, int totalCap, boolean full, int reserveMagazines) {
        if (magazineCapacity < 1 || magazineCapacity > 10000 || totalCap < 1 || reserveMagazines < 0 || reserveMagazines > 10) throw new IllegalArgumentException("Invalid ammo policy");
        int loaded = full ? Math.min(magazineCapacity, totalCap) : 0;
        return new DeploymentAmmoPolicy(loaded, Math.min(totalCap - loaded, magazineCapacity * reserveMagazines));
    }
}
