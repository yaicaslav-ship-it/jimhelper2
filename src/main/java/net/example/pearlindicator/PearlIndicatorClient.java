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

        // Индикатор активен только когда в руке эндер-жемчуг
        boolean hasPearl = client.player.getMainHandStack().isOf(Items.ENDER_PEARL)
                || client.player.getOffHandStack().isOf(Items.ENDER_PEARL);

        if (!hasPearl) return;

        boolean canEnterWall = checkCanEnterWall(client);

        String message = canEnterWall ? "МОЖНО!" : "НЕЛЬЗЯ!";
        int color = canEnterWall ? 0xFF22FF22 : 0xFFFF2222;

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        int textWidth = client.textRenderer.getWidth(message);
        int x = (screenWidth - textWidth) / 2;
        int y = (screenHeight / 2) + 12;

        context.drawTextWithShadow(client.textRenderer, Text.literal(message), x, y, color);
    }

    private static boolean checkCanEnterWall(MinecraftClient client) {
        Entity player = client.player;
        World world = client.world;
        if (player == null || world == null) return false;

        Vec3d pos = player.getCameraPosVec(1.0F).subtract(0.0, 0.1, 0.0);

        float pitch = player.getPitch();
        float yaw = player.getYaw();

        float radYaw = yaw * ((float) Math.PI / 180.0F);
        float radPitch = pitch * ((float) Math.PI / 180.0F);

        float xDir = -MathHelper.sin(radYaw) * MathHelper.cos(radPitch);
        float yDir = -MathHelper.sin(radPitch);
        float zDir = MathHelper.cos(radYaw) * MathHelper.cos(radPitch);

        Vec3d velocity = new Vec3d(xDir, yDir, zDir).normalize().multiply(1.5D);

        BlockHitResult wallHit = null;

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
                wallHit = hit;
                break;
            }

            pos = nextPos;
            velocity = velocity.multiply(0.99D).subtract(0.0, 0.03D, 0.0);
        }

        // Если перка улетела в воздух/пустоту — в стену не попадаем
        if (wallHit == null) {
            return false;
        }

        BlockPos hitPos = wallHit.getBlockPos();
        Direction side = wallHit.getSide();
        BlockState state = world.getBlockState(hitPos);

        // 1. Нельзя войти в воздух или жидкости
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }

        // 2. Пол под ногами (грань UP на плоской земле) — это обычное приземление, а не вход в стену
        if (side == Direction.UP && hitPos.getY() <= player.getBlockY()) {
            return false;
        }

        // 3. Попадание в боковую грань стены (NORTH, SOUTH, WEST, EAST)
        if (side.getAxis().isHorizontal()) {
            // Блок твердый/коллизионный — телепортация гарантированно всаживает хитбокс в стену
            return state.isSolidBlock(world, hitPos) || state.isOpaqueFullCube();
        }

        // 4. Попадание снизу блока (потолок) или в угловой стык над головой
        if (side == Direction.DOWN) {
            return state.isSolidBlock(world, hitPos);
        }

        return false;
    }
}
