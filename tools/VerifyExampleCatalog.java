import top.mores.bFBackpack.catalog.WeaponCatalog;
import top.mores.bFBackpack.backpack.BackpackType;
import java.nio.file.Path;

/** Checks example files using the real backend parser, without a Bukkit runtime. */
public final class VerifyExampleCatalog {
    public static void main(String[] args) throws Exception {
        WeaponCatalog catalog=new WeaponCatalog(message->{throw new AssertionError(message);});
        var loaded=catalog.reload(Path.of(args[0]));
        if(loaded.weapons().size()!=9 || loaded.attachments().size()!=1)throw new AssertionError("Example catalog size");
        for(var type:BackpackType.values())if(loaded.rules().defaults(type).size()!=6)throw new AssertionError("Incomplete defaults: "+type);
        System.out.println("EXAMPLE_CATALOG_OK: 9 equipment, 1 attachment, 4 complete class defaults");
    }
}
