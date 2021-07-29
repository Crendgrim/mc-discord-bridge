package selic.re.discordbridge.mixin;

import net.minecraft.network.listener.ServerPlayPacketListener;
import net.minecraft.server.filter.TextStream;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.world.EntityTrackingListener;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import selic.re.discordbridge.DiscordBot;

import java.util.Optional;

@Mixin(ServerPlayNetworkHandler.class)
abstract class NetworkHandlerMixin implements EntityTrackingListener, ServerPlayPacketListener {
    @Inject(
        method = "handleMessage(Lnet/minecraft/server/filter/TextStream$Message;)V",
        at = @At("HEAD"), require = 1)
    private void preMessage(TextStream.Message message, CallbackInfo info) {
        String str = message.getRaw();

        if (str.startsWith("/me ")) {
            /*
             The reason why this has to be handled individually is because in this particular instance,
             the broadcast() method is used; this relies on internal behaviour and should probably
             be changed to match the translatable string rather than hijacking this particular class.
             That said, it works. For now.
            */
            DiscordBot.getInstance().ifPresent(bot -> {
                bot.sendChatMessage(getPlayer().getGameProfile(), "*" + str.substring("/me ".length()) + "*");
            });
        }
    }

    @ModifyVariable(method = "handleMessage(Lnet/minecraft/server/filter/TextStream$Message;)V", at = @At("STORE"), ordinal = 1)
    private Text replaceRawChatInput(Text original) {
        Optional<DiscordBot> bot = DiscordBot.getInstance();
        if (bot.isPresent() && original instanceof TranslatableText translatableText && translatableText.getArgs().length == 2 && translatableText.getArgs()[0] instanceof Text author && translatableText.getArgs()[1] instanceof String message) {
            return new TranslatableText("chat.type.text", author, bot.get().broadcastAndReplaceChatMessage(getPlayer().getGameProfile(), message));
        }
        return original;
    }
}
