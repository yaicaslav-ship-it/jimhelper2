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
        int color = hitsEntity ? 0xFFFF2222 : 0xFF22FF22;

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

        // Ванильная точка спавна жемчуга
        Vec3d pos = player.getCameraPosVec(1.0F).subtract(0, 0.1, 0);

        float pitch = player.getPitch();
        float yaw = player.getYaw();

        float xDir = -MathHelper.sin(yaw * 0.017453292F) * MathHelper.cos(pitch * 0.017453292F);
        float yDir = -MathHelper.sin(pitch * 0.017453292F);
        float zDir = MathHelper.cos(yaw * 0.017453292F) * MathHelper.cos(pitch * 0.017453292F);

        // Начальная скорость жемчуга = 1.5
        Vec3d velocity = new Vec3d(xDir, yDir, zDir).normalize().multiply(1.5D);

        // Размер хитбокса жемчуга (0.25x0.25), радиус расширения = 0.15 - 0.2
        final double pearlRadius = 0.16D;

        for (int i = 0; i < 120; i++) {
            Vec3d nextPos = pos.add(velocity);

            // 1. Проверяем блок по траектории
            BlockHitResult blockHit = world.raycast(new RaycastContext(
                    pos,
                    nextPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    player
            ));

            Vec3d maxReach = (blockHit.getType() != HitResult.Type.MISS) ? blockHit.getPos() : nextPos;
            double blockDistSq = pos.squaredDistanceTo(maxReach);

            // 2. Ищем всех сущностей в области шага
            Box stepSearchBox = new Box(pos, maxReach).expand(1.5);
            List<Entity> candidates = world.getOtherEntities(player, stepSearchBox, 
                    e -> !e.isSpectator() && e.canHit() && e.isAlive());

            for (Entity entity : candidates) {
                // Расширяем хитбокс сущности на радиус жемчуга + отступ взаимодействия
                Box targetBox = entity.getBoundingBox().expand(entity.getTargetingMargin() + pearlRadius);

                // Проверяем прямое пересечение отрезка полета с объемом хитбокса
                Optional<Vec3d> hitPoint = targetBox.raycast(pos, maxReach);
                if (hitPoint.isPresent()) {
                    double entityDistSq = pos.squaredDistanceTo(hitPoint.get());
                    // Если задели хитбокс до того, как врезались в блок
                    if (entityDistSq <= blockDistSq) {
                        return true;
                    }
                }

                // Дополнительная проверка на случай, если точка спавна перки уже внутри границы
                if (targetBox.contains(pos)) {
                    return true;
                }
            }

            // Если попали в блок и сущностей на пути не было
            if (blockHit.getType() != HitResult.Type.MISS) {
                return false;
            }

            pos = nextPos;
            // Физика снаряда
            velocity = velocity.multiply(0.99).subtract(0, 0.03, 0);
        }

        return false;
    }
}
