package net.example.pearlindicator;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

public class PearlIndicatorClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        HudRenderCallback.EVENT.register(this::onRenderHud);
    }

    private void onRenderHud(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        // Проверяем, держит ли игрок эндер-жемчуг в руках
        boolean holdingPearl = client.player.getMainHandStack().isOf(Items.ENDER_PEARL)
                || client.player.getOffHandStack().isOf(Items.ENDER_PEARL);

        if (!holdingPearl) return;

        // Проверяем траекторию броска
        boolean hitsEntity = simulatePearl(client);

        String text = hitsEntity ? "НЕЛЬЗЯ!" : "МОЖНО!";
        int color = hitsEntity ? 0xFFFF0000 : 0xFF00FF00; // Красный или Зеленый

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        // Отображение текста под прицелом
        int textWidth = client.textRenderer.getWidth(text);
        int x = (screenWidth - textWidth) / 2;
        int y = (screenHeight / 2) + 12;

        context.drawTextWithShadow(client.textRenderer, text, x, y, color);
    }

    private boolean simulatePearl(MinecraftClient client) {
        Entity shooter = client.player;
        World world = client.world;

        Vec3d pos = shooter.getCameraPosVec(1.0F).subtract(0, 0.1, 0);

        float pitch = shooter.getPitch();
        float yaw = shooter.getYaw();
        float roll = 0.0F;

        float f = -MathHelper.sin(yaw * 0.017453292F) * MathHelper.cos(pitch * 0.017453292F);
        float g = -MathHelper.sin((pitch + roll) * 0.017453292F);
        float h = MathHelper.cos(yaw * 0.017453292F) * MathHelper.cos(pitch * 0.017453292F);

        Vec3d velocity = new Vec3d(f, g, h).normalize().multiply(1.5D);

        int maxTicks = 100;

        for (int i = 0; i < maxTicks; i++) {
            Vec3d nextPos = pos.add(velocity);

            BlockHitResult blockHit = world.raycast(new RaycastContext(
                    pos,
                    nextPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    shooter
            ));

            Vec3d checkEndPos = (blockHit.getType() != HitResult.Type.MISS) ? blockHit.getPos() : nextPos;

            Box box = new Box(pos, checkEndPos).expand(1.0);
            EntityHitResult entityHit = ProjectileUtil.raycast(
                    shooter,
                    pos,
                    checkEndPos,
                    box,
                    entity -> !entity.isSpectator() && entity.canHit(),
                    pos.squaredDistanceTo(checkEndPos)
            );

            if (entityHit != null && entityHit.getType() == HitResult.Type.ENTITY) {
                return true;
            }

            if (blockHit.getType() != HitResult.Type.MISS) {
                return false;
            }

            pos = nextPos;
            velocity = velocity.multiply(0.99).subtract(0, 0.03, 0);
        }

        return false;
    }
}
