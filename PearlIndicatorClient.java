package net.example.pearlindicator;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.List;
import java.util.Optional;

public class PearlIndicatorClient implements ClientModInitializer {

    // Реальный физический полуразмер жемчуга края (0.25 / 2 = 0.125)
    private static final double PEARL_RADIUS = 0.125D;

    @Override
    public void onInitializeClient() {
    }

    public static void render(DrawContext context, MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) return;
        if (client.options.hudHidden) return;

        boolean hasPearl = client.player.getMainHandStack().isOf(Items.ENDER_PEARL)
                || client.player.getOffHandStack().isOf(Items.ENDER_PEARL);

        if (!hasPearl) return;

        boolean hitsEntity = simulateTrajectory(client);

        String message = hitsEntity ? "НЕЛЬЗЯ!" : "МОЖНО!";
        int color = hitsEntity ? 0xFFFF1111 : 0xFF11FF11;

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

        // Позиция спавна жемчуга (глаза игрока с ванильным смещением)
        Vec3d currentPos = player.getCameraPosVec(1.0F).subtract(0.0, 0.1, 0.0);

        float pitch = player.getPitch();
        float yaw = player.getYaw();

        float radYaw = yaw * ((float) Math.PI / 180.0F);
        float radPitch = pitch * ((float) Math.PI / 180.0F);

        float xDir = -MathHelper.sin(radYaw) * MathHelper.cos(radPitch);
        float yDir = -MathHelper.sin(radPitch);
        float zDir = MathHelper.cos(radYaw) * MathHelper.cos(radPitch);

        // Начальная скорость жемчуга = 1.5 блока за тик
        Vec3d velocity = new Vec3d(xDir, yDir, zDir).normalize().multiply(1.5D);

        for (int step = 0; step < 120; step++) {
            Vec3d nextPos = currentPos.add(velocity);

            // 1. Проверяем попадание в блоки
            BlockHitResult blockHit = world.raycast(new RaycastContext(
                    currentPos,
                    nextPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    player
            ));

            boolean hitBlock = (blockHit.getType() != HitResult.Type.MISS);
            Vec3d segmentEnd = hitBlock ? blockHit.getPos() : nextPos;
            double blockDistSq = hitBlock ? currentPos.squaredDistanceTo(segmentEnd) : Double.MAX_VALUE;

            // 2. Ищем сущностей вокруг отрезка полета
            Box stepSearchBox = new Box(currentPos, segmentEnd).expand(1.5D);
            List<Entity> entities = world.getOtherEntities(player, stepSearchBox,
                    e -> !e.isSpectator() && e.canHit() && e.isAlive() && e != player);

            double closestEntityDistSq = Double.MAX_VALUE;

            for (Entity entity : entities) {
                // ВАЖНО: хитбокс сущности + ванильный targeting margin + радиус перла
                double totalExpansion = PEARL_RADIUS + entity.getTargetingMargin();
                Box targetBox = entity.getBoundingBox().expand(totalExpansion);

                // Проверяем попадание луча в объем хитбокса
                Optional<Vec3d> hitPoint = targetBox.raycast(currentPos, segmentEnd);
                if (hitPoint.isPresent()) {
                    double distSq = currentPos.squaredDistanceTo(hitPoint.get());
                    if (distSq < closestEntityDistSq) {
                        closestEntityDistSq = distSq;
                    }
                }
            }

            // 3. Если задели сущность ближе, чем блок — перка гарантированно врежется
            if (closestEntityDistSq <= blockDistSq) {
                return true;
            }

            // Если перка ударилась в блок и никого не задела — бросок успешен
            if (hitBlock) {
                return false;
            }

            // Физика снаряда
            currentPos = nextPos;
            velocity = velocity.multiply(0.99D).subtract(0.0, 0.03D, 0.0);
        }

        return false;
    }
}
