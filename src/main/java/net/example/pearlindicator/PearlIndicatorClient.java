package net.example.pearlindicator;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

public class PearlIndicatorClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
    }

    public static void render(DrawContext context, MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) return;
        if (client.options.hudHidden) return;

        // Показываем индикатор только если в руках эндер-жемчуг
        boolean hasPearl = client.player.getMainHandStack().isOf(Items.ENDER_PEARL)
                || client.player.getOffHandStack().isOf(Items.ENDER_PEARL);

        if (!hasPearl) return;

        // Проверяем возможность пройти сквозь стену
        boolean canPhase = checkWallPass(client);

        String message = canPhase ? "МОЖНО!" : "НЕЛЬЗЯ!";
        int color = canPhase ? 0xFF22FF22 : 0xFFFF2222; // Зеленый / Красный

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        int textWidth = client.textRenderer.getWidth(message);
        int x = (screenWidth - textWidth) / 2;
        int y = (screenHeight / 2) + 12;

        context.drawTextWithShadow(client.textRenderer, Text.literal(message), x, y, color);
    }

    /**
     * Симулирует траекторию перла до блока и проверяет, проходима ли стена
     */
    private static boolean checkWallPass(MinecraftClient client) {
        Entity player = client.player;
        World world = client.world;
        if (player == null || world == null) return false;

        // Позиция броска
        Vec3d pos = player.getCameraPosVec(1.0F).subtract(0.0, 0.1, 0.0);

        float pitch = player.getPitch();
        float yaw = player.getYaw();

        float radYaw = yaw * ((float) Math.PI / 180.0F);
        float radPitch = pitch * ((float) Math.PI / 180.0F);

        float xDir = -MathHelper.sin(radYaw) * MathHelper.cos(radPitch);
        float yDir = -MathHelper.sin(radPitch);
        float zDir = MathHelper.cos(radYaw) * MathHelper.cos(radPitch);

        Vec3d velocity = new Vec3d(xDir, yDir, zDir).normalize().multiply(1.5D);

        BlockHitResult finalHit = null;

        // Симуляция полета перки
        for (int step = 0; step < 120; step++) {
            Vec3d nextPos = pos.add(velocity);

            BlockHitResult hit = world.raycast(new RaycastContext(
                    pos,
                    nextPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    player
            ));

            if (hit.getType() != HitResult.Type.MISS) {
                finalHit = hit;
                break;
            }

            pos = nextPos;
            velocity = velocity.multiply(0.99D).subtract(0.0, 0.03D, 0.0);
        }

        // Если перка улетела в воздух/пустоту — в стену мы не попадаем
        if (finalHit == null) {
            return false;
        }

        BlockPos hitBlockPos = finalHit.getBlockPos();
        Direction hitSide = finalHit.getSide();

        // Проверяем условия для прохождения сквозь блок
        return isWallPenetrable(world, hitBlockPos, hitSide);
    }

    /**
     * Логика проверки проходимости стены/блока
     */
    private static boolean isWallPenetrable(World world, BlockPos hitPos, Direction hitSide) {
        BlockState hitState = world.getBlockState(hitPos);

        // 1. Неполные блоки (плиты, ступени, люки, заборы, двери) — перка почти всегда клипает сквозь них
        if (!hitState.isOpaqueFullCube()) {
            return true;
        }

        // 2. Вектор внутрь стены (направление, противоположное грани удара)
        Direction inward = hitSide.getOpposite();

        // Блок прямо за тем, в который попала перка (толщина стены = 1 блок)
        BlockPos behindPos = hitPos.offset(inward);
        BlockState behindState = world.getBlockState(behindPos);
        BlockState behindAboveState = world.getBlockState(behindPos.up());

        // Если за 1-м блоком стены находится воздух или пустота (ширина стены 1 блок)
        boolean hasSpaceBehind = (!behindState.isOpaqueFullCube() || behindState.isAir()) 
                && (!behindAboveState.isOpaqueFullCube() || behindAboveState.isAir());

        if (hasSpaceBehind) {
            return true;
        }

        // 3. Проверка клипа через угол (диагональный вход в блок на стыке)
        BlockPos adjacentCorner = hitPos.offset(inward).offset(hitSide);
        if (world.getBlockState(adjacentCorner).isAir()) {
            return true;
        }

        // Стена глухая и толстая (2+ цельных блоков) — зайти не получится
        return false;
    }
}
