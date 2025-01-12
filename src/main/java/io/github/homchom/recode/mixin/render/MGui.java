package io.github.homchom.recode.mixin.render;

import io.github.homchom.recode.render.SideChatComponent;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Gui.class)
public abstract class MGui {
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lnet/minecraft/client/gui/GuiGraphics;III)V"
    ))
    public void renderSideChat(ChatComponent chat, GuiGraphics graphics, int tickDelta, int mouseX, int mouseY) {
        chat.render(graphics, tickDelta, mouseX, mouseY);
        ((SideChatComponent) chat).renderSide(graphics, tickDelta, mouseX, mouseY);
    }
}
