package selic.re.discordbridge.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.encryption.PlayerPublicKey;
import net.minecraft.network.message.MessageType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import selic.re.discordbridge.DiscordBot;

@Mixin(ServerPlayerEntity.class)
abstract class ServerPlayerEntityMixin extends PlayerEntity {

    public ServerPlayerEntityMixin(World world, BlockPos pos, float yaw, GameProfile gameProfile, @Nullable PlayerPublicKey publicKey) {
        super(world, pos, yaw, gameProfile, publicKey);
    }

    @Inject(
        method = "getPlayerListName()Lnet/minecraft/text/Text;",
        at = @At("HEAD"), require = 1, cancellable = true)
    private void getPlayerListName(CallbackInfoReturnable<Text> ci) {
        Text name = DiscordBot.instance().getDiscordName(this);
        if (name != null) {
            ci.setReturnValue(name);
        }
    }

    @Inject(
        method = "acceptsMessage",
        at = @At("HEAD"), require = 1, cancellable = true)
    private void acceptsMessage(RegistryKey<MessageType> type, CallbackInfoReturnable<Boolean> ci) {
        if (type == MessageType.CHAT && DiscordBot.instance().isChatHidden(this)) {
            ci.setReturnValue(false);
        }
    }
}
