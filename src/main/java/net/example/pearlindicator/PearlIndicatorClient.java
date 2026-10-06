package net.example.pearlindicator;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
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
        // Инициализация мода
    }

    public static void render(DrawContext context, MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) return;
        if (client.options.hudHidden) return; // Если нажат F1

        // Проверяем жемчуг в руках
        boolean hasPearl = client.player.getMainHandStack().isOf(Items.ENDER_PEARL)
                || client.player.getOffHandStack().isOf(Items.ENDER_PEARL);

        if (!hasPearl) return;

        boolean hitsEntity = simulateTrajectory(client);

        String message = hitsEntity ? "НЕЛЬЗЯ!" : "МОЖНО!";
        int color = hitsEntity ? 0xFFFF0000 : 0xFF00FF00;

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        int textWidth = client.textRenderer.getWidth(message);
        int x = (screenWidth - textWidth) / 2;
        int y = (screenHeight / 2) + 12;

        context.drawTextWithShadow(client.textRenderer, Text.literal(message), x, y, color);
    }

    private static boolean simulateTrajectory(MinecraftClient client) {
        Entity player = client.player;
        World world = client.world;
        if (player == null || world == null) return false;

        Vec3d pos = player.getCameraPosVec(1.0F).subtract(0, 0.1, 0);

        float pitch = player.getPitch();
        float yaw = player.getYaw();

        float xDir = -MathHelper.sin(yaw * 0.017453292F) * MathHelper.cos(pitch * 0.017453292F);
        float yDir = -MathHelper.sin(pitch * 0.017453292F);
        float zDir = MathHelper.cos(yaw * 0.017453292F) * MathHelper.cos(pitch * 0.017453292F);

        Vec3d velocity = new Vec3d(xDir, yDir, zDir).normalize().multiply(1.5D);

        for (int i = 0; i < 100; i++) {
            Vec3d nextPos = pos.add(velocity);

            // Проверка блока
            BlockHitResult blockHit = world.raycast(new RaycastContext(
                    pos,
                    nextPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    player
            ));

            Vec3d checkEnd = (blockHit.getType() != HitResult.Type.MISS) ? blockHit.getPos() : nextPos;

            // Проверка сущностей (хитбоксов игроков / мобов)
            Box box = new Box(pos, checkEnd).expand(1.0);
            EntityHitResult entityHit = ProjectileUtil.raycast(
                    player,
                    pos,
                    checkEnd,
                    box,
                    e -> !e.isSpectator() && e.canHit() && e != player,
                    pos.squaredDistanceTo(checkEnd)
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
