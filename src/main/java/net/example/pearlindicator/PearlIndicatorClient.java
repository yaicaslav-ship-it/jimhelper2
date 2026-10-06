package net.example.pearlindicator;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PearlIndicatorClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("pearlindicator");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[PearlIndicator] Мод запущен на клиенте 1.21.4!");

        HudRenderCallback.EVENT.register((DrawContext drawContext, RenderTickCounter tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.player == null || client.world == null) {
                return;
            }

            // Не рендерим, если открыт инвентарь, меню паузы или чат
            if (client.currentScreen != null) {
                return;
            }

            // Проверяем наличие эндер-перла в любой из двух рук
            boolean mainHand = client.player.getMainHandStack().isOf(Items.ENDER_PEARL);
            boolean offHand = client.player.getOffHandStack().isOf(Items.ENDER_PEARL);

            if (!mainHand && !offHand) {
                return;
            }

            boolean hitsEntity = simulatePearl(client);

            String message = hitsEntity ? "НЕЛЬЗЯ!" : "МОЖНО!";
            // Яркий красный (0xFFFF2020) или ярко-салатовый (0xFF20FF20)
            int color = hitsEntity ? 0xFFFF2020 : 0xFF20FF20;

            // В 1.21.4 надежнее всего брать ширину экрана из client.getWindow()
            int screenWidth = client.getWindow().getScaledWidth();
            int screenHeight = client.getWindow().getScaledHeight();

            int textWidth = client.textRenderer.getWidth(message);
            int x = (screenWidth - textWidth) / 2;
            int y = (screenHeight / 2) + 12;

            // Отрисовка: передаем Text объект и флаг shadow = true
            drawContext.drawText(
                client.textRenderer, 
                Text.literal(message), 
                x, 
                y, 
                color, 
                true
            );
        });
    }

    private boolean simulatePearl(MinecraftClient client) {
        Entity shooter = client.player;
        World world = client.world;
        if (shooter == null || world == null) return false;

        // Позиция броска с учетом глаз игрока
        Vec3d pos = shooter.getCameraPosVec(1.0F).subtract(0.0, 0.1, 0.0);

        float pitch = shooter.getPitch();
        float yaw = shooter.getYaw();

        float radYaw = yaw * ((float) Math.PI / 180.0F);
        float radPitch = pitch * ((float) Math.PI / 180.0F);

        float xDir = -MathHelper.sin(radYaw) * MathHelper.cos(radPitch);
        float yDir = -MathHelper.sin(radPitch);
        float zDir = MathHelper.cos(radYaw) * MathHelper.cos(radPitch);

        // Начальная скорость жемчуга края в Minecraft = 1.5
        Vec3d velocity = new Vec3d(xDir, yDir, zDir).normalize().multiply(1.5D);

        for (int step = 0; step < 120; step++) {
            Vec3d nextPos = pos.add(velocity);

            // Проверка блока на пути луча
            BlockHitResult blockHit = world.raycast(new RaycastContext(
                    pos,
                    nextPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    shooter
            ));

            Vec3d stepEnd = (blockHit.getType() != HitResult.Type.MISS) ? blockHit.getPos() : nextPos;

            // Проверка сущностей (хитбоксов) на пути текущего шага
            Box rayBox = new Box(pos, stepEnd).expand(0.8D);
            EntityHitResult entityHit = ProjectileUtil.raycast(
                    shooter,
                    pos,
                    stepEnd,
                    rayBox,
                    entity -> !entity.isSpectator() && entity.canHit() && entity != shooter,
                    pos.squaredDistanceTo(stepEnd)
            );

            // Если на пути до препятствия стоит моб/игрок
            if (entityHit != null && entityHit.getType() == HitResult.Type.ENTITY) {
                return true;
            }

            // Если раньше встретился блок — траектория безопасно уперлась в блок
            if (blockHit.getType() != HitResult.Type.MISS) {
                return false;
            }

            pos = nextPos;
            // Коэффициенты физики жемчуга: трение 0.99, гравитация 0.03 за тик
            velocity = velocity.multiply(0.99D).subtract(0.0, 0.03D, 0.0);
        }

        return false;
    }
}
