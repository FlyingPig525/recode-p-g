package me.reasonless.codeutilities.events.mixins;

import me.reasonless.codeutilities.CodeUtilities;
import me.reasonless.codeutilities.events.ActionbarReceivedEvent;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.MessageType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientChatListenerMixin {

  @Inject(method = "onChatMessage", at = @At("HEAD"), cancellable = true)
  private void handleMessage(ChatMessageS2CPacket packet, CallbackInfo ci) {
	  if (!CodeUtilities.hasblazing) {
		  if (packet.getLocation() == MessageType.GAME_INFO) {
		      ActionbarReceivedEvent.onMessage(packet.getMessage(), ci);
		    }
	  }
  }
}