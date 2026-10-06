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

    // Максимальная дистанция для клипа в стену (в блоках)
    private static final double MAX_PHASE_DISTANCE = 4.2D;
    // Допустимая зона стыка (18% от края грани блока)
    private static final double SEAM_THRESHOLD = 0.18D;

    @Override
    public void onInitializeClient() {
    }

    public static void render(DrawContext context, MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) return;
        if (client.options.hudHidden) return;

        boolean hasPearl = client.player.getMainHandStack().isOf(Items.ENDER_PEARL)
                || client.player.getOffHandStack().isOf(Items.ENDER_PEARL);

        if (!hasPearl) return;

        boolean canClip = checkCanClipIntoWall(client);

        String message = canClip ? "МОЖНО!" : "НЕЛЬЗЯ!";
        int color = canClip ? 0xFF22FF22 : 0xFFFF2222;

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        int textWidth = client.textRenderer.getWidth(message);
        int x = (screenWidth - textWidth) / 2;
        int y = (screenHeight / 2) + 12;

        context.drawTextWithShadow(client.textRenderer, Text.literal(message), x, y, color);
    }

    private static boolean checkCanClipIntoWall(MinecraftClient client) {
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

        BlockHitResult finalHit = null;

        for (int step = 0; step < 100; step++) {
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

        if (finalHit == null) {
            return false;
        }

        Vec3d hitVec = finalHit.getPos();
        BlockPos hitPos = finalHit.getBlockPos();
        Direction side = finalHit.getSide();

        // 1. Проверка дистанции: далеко от стены клипнуться невозможно
        if (player.getEyePos().distanceTo(hitVec) > MAX_PHASE_DISTANCE) {
            return false;
        }

        BlockState hitState = world.getBlockState(hitPos);
        if (hitState.isAir() || !hitState.getFluidState().isEmpty()) {
            return false;
        }

        // 2. Неполные блоки (люки, плиты, ступеньки) — клипают всегда
        if (!hitState.isOpaqueFullCube() && hitState.blocksMovement()) {
            return true;
        }

        // Расчёт координат точки удара внутри блока [0.0 ... 1.0]
        double fracX = hitVec.x - Math.floor(hitVec.x);
        double fracY = hitVec.y - Math.floor(hitVec.y);
        double fracZ = hitVec.z - Math.floor(hitVec.z);

        double edgeX = Math.min(fracX, 1.0 - fracX);
        double edgeY = Math.min(fracY, 1.0 - fracY);
        double edgeZ = Math.min(fracZ, 1.0 - fracZ);

        // 3. Попадание в вертикальную стену (NORTH, SOUTH, EAST, WEST)
        if (side.getAxis().isHorizontal()) {
            // Верхний стык (под потолком) — классический фазинг (скриншот 1)
            if (fracY >= (1.0 - SEAM_THRESHOLD) && isSolidBlock(world, hitPos.up())) {
                return true;
            }

            // Нижний стык (у пола)
            if (fracY <= SEAM_THRESHOLD && isSolidBlock(world, hitPos.down())) {
                return true;
            }

            // Внутренние углы между двумя стенами
            if (side == Direction.NORTH || side == Direction.SOUTH) {
                if (fracX <= SEAM_THRESHOLD && isSolidBlock(world, hitPos.west())) return true;
                if (fracX >= (1.0 - SEAM_THRESHOLD) && isSolidBlock(world, hitPos.east())) return true;
            } else {
                if (fracZ <= SEAM_THRESHOLD && isSolidBlock(world, hitPos.north())) return true;
                if (fracZ >= (1.0 - SEAM_THRESHOLD) && isSolidBlock(world, hitPos.south())) return true;
            }

            // Центр плоской стены без стыка (скриншот 2) — зайти нельзя!
            return false;
        }

        // 4. Попадание в нижнюю грань потолка (DOWN)
        if (side == Direction.DOWN) {
            // Если попадание в потолок вплотную к стене — происходит клип в угол
            boolean nearWallX = edgeX <= 0.22D && (isSolidBlock(world, hitPos.west()) || isSolidBlock(world, hitPos.east()));
            boolean nearWallZ = edgeZ <= 0.22D && (isSolidBlock(world, hitPos.north()) || isSolidBlock(world, hitPos.south()));
            return nearWallX || nearWallZ;
        }

        // 5. Попадание в пол (UP)
        if (side == Direction.UP) {
            // Только если перл кинут ровно в основание вертикальной стены
            boolean baseWallX = edgeX <= 0.15D && (isSolidBlock(world, hitPos.west()) || isSolidBlock(world, hitPos.east()));
            boolean baseWallZ = edgeZ <= 0.15D && (isSolidBlock(world, hitPos.north()) || isSolidBlock(world, hitPos.south()));
            return baseWallX || baseWallZ;
        }

        return false;
    }

    private static boolean isSolidBlock(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty() && (state.isOpaqueFullCube() || state.blocksMovement());
    }
}
