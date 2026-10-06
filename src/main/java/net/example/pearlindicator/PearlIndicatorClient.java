package net.example.pearlindicator;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
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

    // Допустимая зона угла/стыка (30% от края грани)
    private static final double CORNER_MARGIN = 0.30D;
    // Максимальная дистанция броска для фазинга
    private static final double MAX_DISTANCE = 3.5D;

    @Override
    public void onInitializeClient() {
    }

    public static void render(DrawContext context, MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) return;
        if (client.options.hudHidden) return;

        boolean hasPearl = client.player.getMainHandStack().isOf(Items.ENDER_PEARL)
                || client.player.getOffHandStack().isOf(Items.ENDER_PEARL);

        if (!hasPearl) return;

        boolean canEnter = checkWallPhase(client);

        String message = canEnter ? "МОЖНО!" : "НЕЛЬЗЯ!";
        int color = canEnter ? 0xFF22FF22 : 0xFFFF2222;

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        int textWidth = client.textRenderer.getWidth(message);
        int x = (screenWidth - textWidth) / 2;
        int y = (screenHeight / 2) + 12;

        context.drawTextWithShadow(client.textRenderer, Text.literal(message), x, y, color);
    }

    private static boolean checkWallPhase(MinecraftClient client) {
        PlayerEntity player = client.player;
        World world = client.world;
        if (player == null || world == null) return false;

        Vec3d eyePos = player.getCameraPosVec(1.0F);
        Vec3d throwPos = eyePos.subtract(0.0, 0.1, 0.0);

        float pitch = player.getPitch();
        float yaw = player.getYaw();

        float radYaw = yaw * ((float) Math.PI / 180.0F);
        float radPitch = pitch * ((float) Math.PI / 180.0F);

        float xDir = -MathHelper.sin(radYaw) * MathHelper.cos(radPitch);
        float yDir = -MathHelper.sin(radPitch);
        float zDir = MathHelper.cos(radYaw) * MathHelper.cos(radPitch);

        Vec3d velocity = new Vec3d(xDir, yDir, zDir).normalize().multiply(1.5D);

        BlockHitResult hit = null;
        Vec3d currentPos = throwPos;

        for (int step = 0; step < 80; step++) {
            Vec3d nextPos = currentPos.add(velocity);

            BlockHitResult r = world.raycast(new RaycastContext(
                    currentPos,
                    nextPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    player
            ));

            if (r.getType() != HitResult.Type.MISS) {
                hit = r;
                break;
            }

            currentPos = nextPos;
            velocity = velocity.multiply(0.99D).subtract(0.0, 0.03D, 0.0);
        }

        if (hit == null) return false;

        Vec3d hitPos = hit.getPos();
        BlockPos targetPos = hit.getBlockPos();
        Direction side = hit.getSide();

        // Проверка максимальной дистанции
        if (player.getEyePos().distanceTo(hitPos) > MAX_DISTANCE) {
            return false;
        }

        BlockState state = world.getBlockState(targetPos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }

        // Неполные твердые блоки (люки, плиты, ступени) клипают всегда
        if (!state.isOpaqueFullCube() && state.blocksMovement()) {
            return true;
        }

        // Дробные координаты внутри блока [0.0 ... 1.0]
        double fracX = hitPos.x - Math.floor(hitPos.x);
        double fracY = hitPos.y - Math.floor(hitPos.y);
        double fracZ = hitPos.z - Math.floor(hitPos.z);

        // 1. Попадание в вертикальную стену
        if (side.getAxis().isHorizontal()) {
            // Горизонтальный стык: под потолком
            if (fracY >= (1.0 - CORNER_MARGIN) && isSolid(world, targetPos.up())) {
                return true;
            }

            // Горизонтальный стык: у пола
            if (fracY <= CORNER_MARGIN && isSolid(world, targetPos.down())) {
                return true;
            }

            // Вертикальный внутренний угол (стык двух стен)
            if (side == Direction.NORTH || side == Direction.SOUTH) {
                // Край слева (WEST)
                if (fracX <= CORNER_MARGIN && isSolid(world, targetPos.west())) {
                    return true;
                }
                // Край справа (EAST)
                if (fracX >= (1.0 - CORNER_MARGIN) && isSolid(world, targetPos.east())) {
                    return true;
                }
            } else { // WEST или EAST
                // Край спереди (NORTH)
                if (fracZ <= CORNER_MARGIN && isSolid(world, targetPos.north())) {
                    return true;
                }
                // Край сзади (SOUTH)
                if (fracZ >= (1.0 - CORNER_MARGIN) && isSolid(world, targetPos.south())) {
                    return true;
                }
            }

            // Центр плоской стены без углов — клип не сработает
            return false;
        }

        // 2. Попадание в потолок снизу (грань DOWN)
        if (side == Direction.DOWN) {
            double edgeX = Math.min(fracX, 1.0 - fracX);
            double edgeZ = Math.min(fracZ, 1.0 - fracZ);

            boolean nearXWall = edgeX <= CORNER_MARGIN && (isSolid(world, targetPos.west()) || isSolid(world, targetPos.east()));
            boolean nearZWall = edgeZ <= CORNER_MARGIN && (isSolid(world, targetPos.north()) || isSolid(world, targetPos.south()));

            return nearXWall || nearZWall;
        }

        // 3. Попадание в пол сверху (грань UP)
        if (side == Direction.UP) {
            double edgeX = Math.min(fracX, 1.0 - fracX);
            double edgeZ = Math.min(fracZ, 1.0 - fracZ);

            boolean atWallBaseX = edgeX <= 0.20D && (isSolid(world, targetPos.west()) || isSolid(world, targetPos.east()));
            boolean atWallBaseZ = edgeZ <= 0.20D && (isSolid(world, targetPos.north()) || isSolid(world, targetPos.south()));

            return atWallBaseX || atWallBaseZ;
        }

        return false;
    }

    private static boolean isSolid(World world, BlockPos pos) {
        BlockState s = world.getBlockState(pos);
        return !s.isAir() && s.getFluidState().isEmpty() && (s.isOpaqueFullCube() || s.blocksMovement());
    }
}
