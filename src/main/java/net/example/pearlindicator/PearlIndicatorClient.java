package net.example.pearlindicator;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Arm;
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

    // Радиус жемчуга края (0.25 / 2 = 0.125) + серверная погрешность регистрации
    private static final double PEARL_COLLISION_RADIUS = 0.18D;

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

        // Строго по центру экрана под перекрестием прицела
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

        float pitch = player.getPitch();
        float yaw = player.getYaw();

        float radYaw = yaw * ((float) Math.PI / 180.0F);
        float radPitch = pitch * ((float) Math.PI / 180.0F);

        float xDir = -MathHelper.sin(radYaw) * MathHelper.cos(radPitch);
        float yDir = -MathHelper.sin(radPitch);
        float zDir = MathHelper.cos(radYaw) * MathHelper.cos(radPitch);

        // Начальный вектор скорости жемчуга (скорость 1.5)
        Vec3d velocity = new Vec3d(xDir, yDir, zDir).normalize().multiply(1.5D);

        // Учитываем руку, из которой кидается перка
        boolean offHandPearl = !client.player.getMainHandStack().isOf(Items.ENDER_PEARL) 
                && client.player.getOffHandStack().isOf(Items.ENDER_PEARL);
        boolean isRightArm = (client.player.getMainArm() == Arm.RIGHT) != offHandPearl;
        double sideOffset = isRightArm ? 0.1D : -0.1D;

        // Вектор сдвига вбок относительно направления взгляда
        Vec3d sideVec = new Vec3d(-MathHelper.cos(radYaw), 0, -MathHelper.sin(radYaw)).multiply(sideOffset);

        // Точка вылета из глаз со смещением к руке
        Vec3d currentPos = player.getCameraPosVec(1.0F).subtract(0.0, 0.1, 0.0).add(sideVec);

        for (int step = 0; step < 100; step++) {
            Vec3d nextPos = currentPos.add(velocity);

            // 1. Проверяем блок на полном шаге
            BlockHitResult blockHit = world.raycast(new RaycastContext(
                    currentPos,
                    nextPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    player
            ));

            boolean hitBlock = (blockHit.getType() != HitResult.Type.MISS);
            Vec3d blockEnd = hitBlock ? blockHit.getPos() : nextPos;
            double blockDistSq = hitBlock ? currentPos.squaredDistanceTo(blockEnd) : Double.MAX_VALUE;

            // 2. Ищем сущностей вокруг пути снаряда
            Box searchBox = new Box(currentPos, blockEnd).expand(1.5D);
            List<Entity> entities = world.getOtherEntities(player, searchBox,
                    e -> !e.isSpectator() && e.canHit() && e.isAlive() && e != player);

            // Дробим отрезок тика на 5 суб-шагов для сверхточного отлова в упор
            int subSteps = 5;
            Vec3d subDelta = blockEnd.subtract(currentPos).multiply(1.0D / subSteps);
            Vec3d subStart = currentPos;

            for (int sub = 0; sub < subSteps; sub++) {
                Vec3d subEnd = subStart.add(subDelta);

                for (Entity entity : entities) {
                    // Хитбокс сущности + размер перки + отступ
                    Box targetBox = entity.getBoundingBox().expand(PEARL_COLLISION_RADIUS);

                    // Проверяем прямое пересечение суб-отрезка с коробкой
                    Optional<Vec3d> hitPoint = targetBox.raycast(subStart, subEnd);
                    if (hitPoint.isPresent() || targetBox.contains(subStart) || targetBox.contains(subEnd)) {
                        double entityDistSq = currentPos.squaredDistanceTo(subStart);
                        if (entityDistSq <= blockDistSq) {
                            return true; // Столкновение с сущностью
                        }
                    }
                }

                subStart = subEnd;
            }

            // Если попали в блок и сущностей до него не встретили — бросок чистый
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
