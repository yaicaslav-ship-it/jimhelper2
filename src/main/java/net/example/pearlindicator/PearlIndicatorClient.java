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

public class PearlIndicatorClient implements ClientModInitializer {

    // Полный эффективный радиус коллизии перки с учетом хитбокса и серверного буфера
    private static final double PEARL_RADIUS = 0.32D;

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

        // Ванильная точка вылета: глаза игрока - 0.1 блока по высоте
        Vec3d pos = player.getCameraPosVec(1.0F).subtract(0.0, 0.1, 0.0);

        float pitch = player.getPitch();
        float yaw = player.getYaw();

        float radYaw = yaw * ((float) Math.PI / 180.0F);
        float radPitch = pitch * ((float) Math.PI / 180.0F);

        float xDir = -MathHelper.sin(radYaw) * MathHelper.cos(radPitch);
        float yDir = -MathHelper.sin(radPitch);
        float zDir = MathHelper.cos(radYaw) * MathHelper.cos(radPitch);

        // Начальная скорость жемчуга = 1.5 блока за тик
        Vec3d velocity = new Vec3d(xDir, yDir, zDir).normalize().multiply(1.5D);

        for (int step = 0; step < 100; step++) {
            Vec3d nextPos = pos.add(velocity);

            // 1. Проверяем блок на пути
            BlockHitResult blockHit = world.raycast(new RaycastContext(
                    pos,
                    nextPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    player
            ));

            boolean hitBlock = (blockHit.getType() != HitResult.Type.MISS);
            Vec3d stepEnd = hitBlock ? blockHit.getPos() : nextPos;
            double blockDistSq = pos.squaredDistanceTo(stepEnd);

            // 2. Ищем сущностей вокруг пути снаряда
            Box searchBox = new Box(pos, stepEnd).expand(2.0D);
            List<Entity> entities = world.getOtherEntities(player, searchBox,
                    e -> !e.isSpectator() && e.canHit() && e.isAlive() && e != player);

            for (Entity entity : entities) {
                // Расширенный хитбокс сущности (включая толщину перки)
                Box targetBox = entity.getBoundingBox().expand(PEARL_RADIUS);

                // Дистанция от отрезка движения перки [pos -> stepEnd] до коробки цели
                if (intersectsOrClose(pos, stepEnd, targetBox)) {
                    double distToTargetSq = pos.squaredDistanceTo(entity.getEyePos());
                    // Если сущность ближе, чем стена/блок
                    if (!hitBlock || distToTargetSq <= blockDistSq + 1.5D) {
                        return true;
                    }
                }
            }

            // Если врезались в блок
            if (hitBlock) {
                return false;
            }

            pos = nextPos;
            velocity = velocity.multiply(0.99D).subtract(0.0, 0.03D, 0.0);
        }

        return false;
    }

    /**
     * Проверяет, проходит ли отрезок движения перки сквозь или вплотную к хитбоксу
     */
    private static boolean intersectsOrClose(Vec3d start, Vec3d end, Box box) {
        if (box.contains(start) || box.contains(end)) {
            return true;
        }
        if (box.raycast(start, end).isPresent()) {
            return true;
        }

        // Проверка промежуточных точек (10 шагов вдоль отрезка за тик)
        for (int i = 1; i < 10; i++) {
            double factor = i / 10.0D;
            double x = start.x + (end.x - start.x) * factor;
            double y = start.y + (end.y - start.y) * factor;
            double z = start.z + (end.z - start.z) * factor;

            if (box.contains(x, y, z)) {
                return true;
            }
        }

        return false;
    }
}
