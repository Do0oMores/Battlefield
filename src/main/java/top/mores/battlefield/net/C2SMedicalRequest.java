package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.game.medical.MedicalPouchService;
import java.util.function.Supplier;

/** Intention only: no client actor, stock, target, amount, deadline, or completion message. */
public record C2SMedicalRequest(java.util.UUID life, long revision, boolean distribute) {
    public static void encode(C2SMedicalRequest p, FriendlyByteBuf b) { b.writeUUID(p.life); b.writeLong(p.revision); b.writeBoolean(p.distribute); }
    public static C2SMedicalRequest decode(FriendlyByteBuf b) { return new C2SMedicalRequest(b.readUUID(),b.readLong(),b.readBoolean()); }
    /** Untrusted epoch context is compared with server state; it never supplies player identity or stock. */
    public boolean matches(top.mores.battlefield.game.medical.MedicalState state) {
        return life.equals(state.life) && revision == state.revision();
    }
    public static void handle(C2SMedicalRequest p, Supplier<NetworkEvent.Context> supplier) {
        var ctx = supplier.get();
        ctx.enqueueWork(() -> {
            var sender = ctx.getSender();
            if (sender == null || !PacketRateLimiter.allow(sender, "medical", 4)) return;
            if (!p.matches(MedicalPouchService.state(sender))) return;
            if (p.distribute) MedicalPouchService.distribute(sender); else MedicalPouchService.use(sender);
        });
        ctx.setPacketHandled(true);
    }
}
