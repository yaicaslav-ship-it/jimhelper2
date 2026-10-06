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

    // Реальный полуразмер хитбокса жемчуга края (0.25 / 2 = 0.125)
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

        // Позиция броска (глаза игрока с ванильным смещением вниз)
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

        // Имитируем до 120 тиков (6 секунд полета)
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

            // Точка конца отрезка: либо блок, либо конец шага
            boolean hitBlock = (blockHit.getType() != HitResult.Type.MISS);
            Vec3d segmentEnd = hitBlock ? blockHit.getPos() : nextPos;

            // 2. Ищем сущностей вокруг текущего шага
            Box stepSearchBox = new Box(currentPos, segmentEnd).expand(0.5D);
            List<Entity> entities = world.getOtherEntities(player, stepSearchBox,
                    e -> !e.isSpectator() && e.canHit() && e.isAlive() && e != player);

            Entity closestEntity = null;
            double closestDistanceSq = Double.MAX_VALUE;

            for (Entity entity : entities) {
                // Точный хитбокс сущности с расширением на радиус жемчуга
                Box targetBox = entity.getBoundingBox().expand(PEARL_RADIUS);

                // Ищем пересечение именно луча полета с хитбоксом
                Optional<Vec3d> hitPoint = targetBox.raycast(currentPos, segmentEnd);
                if (hitPoint.isPresent()) {
                    double distSq = currentPos.squaredDistanceTo(hitPoint.get());
                    if (distSq < closestDistanceSq) {
                        closestDistanceSq = distSq;
                        closestEntity = entity;
                    }
                }
            }

            // 3. Анализируем результат шага
            if (closestEntity != null) {
                // Если задели сущность, проверяем, не перекрыл ли ее блок раньше
                if (!hitBlock) {
                    return true; // Блока не было, перка попала прямо в сущность
                } else {
                    double blockDistSq = currentPos.squaredDistanceTo(blockHit.getPos());
                    if (closestDistanceSq < blockDistSq) {
                        return true; // Сущность стояла ближе блока
                    } else {
                        return false; // Блок оказался впереди сущности и принял удар
                    }
                }
            }

            // Если попали в блок и сущностей на пути не было — бросок заблокирован стеной/полом
            if (hitBlock) {
                return false;
            }

            // Применяем стандартную физику жемчуга
            currentPos = nextPos;
            velocity = velocity.multiply(0.99D).subtract(0.0, 0.03D, 0.0);
        }

        return false;
    }
}
