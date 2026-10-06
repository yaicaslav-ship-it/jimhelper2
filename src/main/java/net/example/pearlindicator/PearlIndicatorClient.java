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

    // Физический размер хитбокса перки (0.25) + безопасный отступ для сетевого пинга и рассинхрона
    private static final double PEARL_SIZE = 0.25D;
    private static final double PEARL_HALF = PEARL_SIZE / 2.0D;
    private static final double HITBOX_SAFETY_MARGIN = 0.22D; // Гарантирует отлов касаний по краю плеча

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

        // Позиция спавна перки в ванилле
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

        for (int step = 0; step < 120; step++) {
            Vec3d nextPos = pos.add(velocity);

            // 1. Проверяем препятствие (блок) на пути
            BlockHitResult blockHit = world.raycast(new RaycastContext(
                    pos,
                    nextPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    player
            ));

            Vec3d actualEnd = (blockHit.getType() != HitResult.Type.MISS) ? blockHit.getPos() : nextPos;
            double blockDistanceSq = pos.squaredDistanceTo(actualEnd);

            // 2. Формируем объёмный коридор полета перки (Swept Box) с учетом её физического размера
            Box flightPathBox = new Box(
                    Math.min(pos.x, actualEnd.x) - PEARL_HALF,
                    Math.min(pos.y, actualEnd.y) - PEARL_HALF,
                    Math.min(pos.z, actualEnd.z) - PEARL_HALF,
                    Math.max(pos.x, actualEnd.x) + PEARL_HALF,
                    Math.max(pos.y, actualEnd.y) + PEARL_HALF,
                    Math.max(pos.z, actualEnd.z) + PEARL_HALF
            ).expand(0.5);

            List<Entity> nearbyEntities = world.getOtherEntities(player, flightPathBox,
                    e -> !e.isSpectator() && e.canHit() && e.isAlive() && e != player);

            for (Entity entity : nearbyEntities) {
                // Расширяем хитбокс сущности на размер перки + запас на пинг/движение
                Box entityTargetBox = entity.getBoundingBox().expand(
                        PEARL_HALF + HITBOX_SAFETY_MARGIN + entity.getTargetingMargin()
                );

                // Если луч или сама коробка перки пересекает хитбокс
                if (entityTargetBox.raycast(pos, actualEnd).isPresent() || entityTargetBox.intersects(flightPathBox)) {
                    // Проверяем, что сущность находится ближе, чем точка удара о блок
                    double distToEntity = pos.squaredDistanceTo(entity.getPos());
                    if (distToEntity <= blockDistanceSq + 2.0D) {
                        return true;
                    }
                }
            }

            // Если врезались в блок раньше сущности — дальше лететь некуда, бросок успешен
            if (blockHit.getType() != HitResult.Type.MISS) {
                return false;
            }

            pos = nextPos;
            // Ванильная физика жемчуга: сопротивление воздуха 0.99, падение 0.03/тик
            velocity = velocity.multiply(0.99D).subtract(0.0, 0.03D, 0.0);
        }

        return false;
    }
}
