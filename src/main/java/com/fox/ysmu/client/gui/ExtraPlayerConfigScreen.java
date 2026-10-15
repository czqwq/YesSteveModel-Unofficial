package com.fox.ysmu.client.gui;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientEventHandler;
import com.fox.ysmu.util.RenderUtil;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import org.lwjgl.input.Keyboard;

import java.util.List;

public class ExtraPlayerConfigScreen extends GuiScreen {
    private static final char RESET_KEY = 'r';
    /** CU-09: 与 Config.PLAYER_SCALE 声明的范围（8.0..360.0）一致，避免拖拽产生 0/负值。 */
    private static final float MIN_SCALE = 8.0F;
    private static final float MAX_SCALE = 360.0F;
    private int posX;
    private int posY;
    private float scale;
    private float yawOffset;
    private boolean isChangePos = false;
    private boolean isChangeScale = false;

    private static final int LEFT_MOUSE_BUTTON = 0;
    private static final int RIGHT_MOUSE_BUTTON = 1;
    private int lastMouseX;

    public ExtraPlayerConfigScreen() {
        this.posX = Config.PLAYER_POS_X;
        this.posY = Config.PLAYER_POS_Y;
        this.scale = (float) Config.PLAYER_SCALE;
        this.yawOffset = (float) Config.PLAYER_YAW_OFFSET;
    }

    /**
     * Must NOT pause the game: this screen is a live preview of an in-game element, exactly like the four other YSMU
     * screens ({@code PlayerModelScreen:479}, {@code PlayerTextureScreen:141}, {@code AnimationRouletteScreen:191},
     * {@code ModelInfoScreen:184}), which all override this. Left at the {@code GuiScreen} default of {@code true},
     * the preview broke in two separate ways in single-player, because {@code Minecraft.isGamePaused()} was true for
     * the whole time this screen was open:
     *
     * <ul>
     *   <li>{@code Minecraft.updateTimer} freezes {@code renderPartialTicks} while paused, so
     *       {@code AnimatedGeoModel.setLivingAnimations} sees a constant {@code seekTime}; the engine's
     *       same-render-state de-duplication then short-circuits every frame and the bones keep their previous
     *       values - the pose freezes (a blink sticks on the closed frame) while the transform still follows the
     *       mouse, so it looks like the preview "keeps swaying but stopped animating".</li>
     *   <li>{@code CustomPlayerModel.setLivingAnimations} gates its whole humanoid-hook branch on
     *       {@code !Minecraft.getMinecraft().isGamePaused()} ({@code client/model/CustomPlayerModel.java:186-187}),
     *       so the paused player fell into the <em>GUI preview</em> branch: neutralised parser values, i.e. the model
     *       was drawn in its preview state - parts the in-game state hides were all shown.</li>
     * </ul>
     *
     * <p>Ported from the reference branch's {@code client/gui/ExtraPlayerConfigScreen.java:30-59}, whose two bullets
     * name the same two failures. The follow-mode preview ({@link Config#HUD_FOLLOW_MODE}) makes this load-bearing
     * rather than cosmetic: a paused preview cannot show a mode change at all.
     */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }


    @Override
    public void drawScreen(int pMouseX, int pMouseY, float pPartialTick) {
        int startX = this.posX;
        int startY = this.posY;
        int endX = (int) (startX + this.scale * 1);
        int endY = (int) (startY + this.scale * 2);

        this.drawVerticalLine(width / 2 - 1, -2, height + 2, 0x9fffffff);
        this.drawHorizontalLine(-2, width + 2, height / 2 - 1, 0x9fffffff);

        this.drawVerticalLine(10, -2, height + 2, 0x9fffffff);
        this.drawVerticalLine(width - 10, -2, height + 2, 0x9fffffff);
        this.drawHorizontalLine(-2, width + 2, 10, 0x9fffffff);
        this.drawHorizontalLine(-2, width + 2, height - 10, 0x9fffffff);

        this.drawVerticalLine(startX, startY, endY, 0xffff0000);
        this.drawVerticalLine(endX, startY, endY, 0xffff0000);
        this.drawHorizontalLine(startX, endX, startY, 0xffff0000);
        this.drawHorizontalLine(startX, endX, endY, 0xffff0000);

        this.drawGradientRect(startX, startY, endX, endY, 0x4fffffff, 0x4fffffff);

        this.drawGradientRect(startX - 5, startY - 5, startX + 5, startY + 5, 0xFF00FF9F, 0xFF00FF9F);
        this.drawGradientRect(endX - 5, endY - 5, endX + 5, endY + 5, 0xFF00009F, 0xFF00009F);

        String mainText = I18n.format("gui.yes_steve_model.extra_player_render.tips").replace("\\n", "\n");
        List<String> textLines = this.fontRendererObj.listFormattedStringToWidth(mainText, 500);
        int y = 15;
        for (String line : textLines) {
            int w = fontRendererObj.getStringWidth(line);
            this.drawString(fontRendererObj, line, width - 15 - w, y, 0xFFFFFF);
            y += 10;
        }

        // HUD FBO cache hint (reference client/gui/ExtraPlayerConfigScreen.java:96-100); toggled on 'C' below.
        // Row 44. Both lang keys already exist.
        String cacheStatus = I18n.format(Config.GUI_HUD_PREVIEW_CACHE
            ? "gui.yes_steve_model.hud_cache.on"
            : "gui.yes_steve_model.hud_cache.off");
        this.drawString(fontRendererObj, cacheStatus, 15, height - 20, Config.GUI_HUD_PREVIEW_CACHE ? 0x55FF55 : 0xFFAA00);

        // HUD follow mode hint (reference client/gui/ExtraPlayerConfigScreen.java:102-120).
        String followKey;
        switch (Config.HUD_FOLLOW_MODE) {
            case Config.HUD_FOLLOW_VANILLA_SMOOTH:
                followKey = "gui.yes_steve_model.hud_follow.smooth";
                break;
            case Config.HUD_FOLLOW_YSM:
                followKey = "gui.yes_steve_model.hud_follow.ysm";
                break;
            case Config.HUD_FOLLOW_YSM_SMOOTH:
                followKey = "gui.yes_steve_model.hud_follow.ysm_smooth";
                break;
            default:
                followKey = "gui.yes_steve_model.hud_follow.vanilla";
                break;
        }
        String followStatus = I18n.format(followKey);
        int followColor = Config.HUD_FOLLOW_MODE == Config.HUD_FOLLOW_VANILLA ? 0xFFFFFF : 0x55FF55;
        this.drawString(fontRendererObj, followStatus, 15, height - 10, followColor);

        if (this.mc.thePlayer != null) {
            // The same frame partial tick the HUD overlay passes, so the doll on this screen poses like the real one.
            RenderUtil.renderPlayerEntity(
                this.mc.thePlayer,
                this.posX,
                this.posY,
                this.scale,
                this.yawOffset,
                50,
                this.mc.timer.renderPartialTicks);
        }
    }


    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        boolean xIn = this.posX - 5 < mouseX && mouseX < this.posX + 5;
        boolean yIn = this.posY - 5 < mouseY && mouseY < this.posY + 5;
        if (button == LEFT_MOUSE_BUTTON && xIn && yIn) {
            this.isChangePos = true;
        }
        int endX = (int) (this.posX + this.scale * 1);
        int endY = (int) (this.posY + this.scale * 2);
        boolean xIn2 = endX - 5 < mouseX && mouseX < endX + 5;
        boolean yIn2 = endY - 5 < mouseY && mouseY < endY + 5;
        if (button == LEFT_MOUSE_BUTTON && xIn2 && yIn2) {
            this.isChangeScale = true;
        }
        if (button == RIGHT_MOUSE_BUTTON) {
            this.lastMouseX = mouseX;
        }
    }


    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        super.mouseMovedOrUp(mouseX, mouseY, state);
        if (state == 0 || state == 1) {
            this.isChangePos = false;
            this.isChangeScale = false;
        }
    }


    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
        if (isChangeScale) {
            double scale1 = mouseX - this.posX;
            double scale2 = (double) (mouseY - this.posY) / 2;
            // CU-09: 拖到绿方块左/上方会得到 0 或负值（会让模型镜像/塌缩），必须钳制到 Config 声明的 8..360。
            this.scale = clampScale((float) Math.min(scale1, scale2));
        }
        if (isChangePos) {
            // CU-09: 位置也钳制在屏幕范围内，避免把渲染位置拖到屏幕外（或拖成负数）。
            this.posX = clampPos(mouseX, this.width);
            this.posY = clampPos(mouseY, this.height);
        }
        float dragX = mouseX - this.lastMouseX;
        if (button == RIGHT_MOUSE_BUTTON) {
            this.yawOffset += (dragX * 2);
        }
        this.lastMouseX = mouseX;
    }

    /** CU-09: 与 {@code Config.PLAYER_SCALE} 声明的范围（8.0..360.0）保持一致。 */
    private static float clampScale(float value) {
        if (value < MIN_SCALE) {
            return MIN_SCALE;
        }
        return value > MAX_SCALE ? MAX_SCALE : value;
    }

    /** CU-09: 位置限定在 [0, screenSize] 内（Config 的 PlayerPosX/Y 声明下限为 0）。 */
    private static int clampPos(int value, int screenSize) {
        if (value < 0) {
            return 0;
        }
        if (screenSize > 0 && value > screenSize) {
            return screenSize;
        }
        return value;
    }


    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        boolean isAltDown = Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
        if (Character.toLowerCase(typedChar) == RESET_KEY && isAltDown) {
            this.posX = 10;
            this.posY = 10;
            this.scale = 40;
            this.yawOffset = 5;
        }
        // Toggle the HUD paperdoll FBO cache (row 44), the reference's 'C' binding
        // (client/gui/ExtraPlayerConfigScreen.java:189-193). Written back to Config here and persisted by
        // onGuiClosed's Config.save(), exactly as the reference orders it. Turning it off makes the HUD channel
        // render every frame again (HudPreviewCache#render's early return).
        //
        // The reference does not invalidate the cache on this key; this port does, because the FBO still holds the
        // frame from before the cache was switched off and nothing else marks it dirty (the rate policy would
        // catch up within one interval, ~83 ms at the 12 Hz floor, but that shows as a visibly stale doll).
        // Calling the existing invalidate() is the reference's own mechanism, not a new one.
        if (keyCode == Keyboard.KEY_C) {
            Config.GUI_HUD_PREVIEW_CACHE = !Config.GUI_HUD_PREVIEW_CACHE;
            ClientEventHandler.invalidateHudPreviewCache();
            return;
        }
        // Cycle the HUD follow mode (0 = vanilla, 1 = vanilla smooth, 2 = ysm, 3 = ysm smooth), the reference's
        // 'M' binding (client/gui/ExtraPlayerConfigScreen.java:194-199). The value is written back to Config here and
        // persisted by onGuiClosed's Config.save(), exactly as the reference orders it.
        //
        // The invalidate call is the reference's (its :197): the cached doll was baked under the old mode's
        // orientation, so it has to be re-drawn before the change can be seen. RenderUtil#ensureFollowMode resets
        // both smoother state machines on the mode switch, which is what stops a stale smoothed angle from
        // jumping across modes.
        if (keyCode == Keyboard.KEY_M) {
            Config.HUD_FOLLOW_MODE = (Config.HUD_FOLLOW_MODE + 1) % 4;
            ClientEventHandler.invalidateHudPreviewCache();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }


    @Override
    public void onGuiClosed() {
        // CU-09: 关闭时再钳制一次，保证写回 Config 的值始终合法（Config.sync* 也会钳制）。
        Config.PLAYER_POS_X = clampPos(this.posX, this.width);
        Config.PLAYER_POS_Y = clampPos(this.posY, this.height);
        Config.PLAYER_SCALE = clampScale(this.scale);
        Config.PLAYER_YAW_OFFSET = this.yawOffset;
        Config.save();
    }
}
