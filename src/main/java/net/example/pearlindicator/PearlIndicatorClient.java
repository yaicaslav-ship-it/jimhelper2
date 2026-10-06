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
import net.minecraft.util.math.Box;
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

        boolean hasPearl = client.player.getMainHandStack().isOf(Items.ENDER_PEARL)
                || client.player.getOffHandStack().isOf(Items.ENDER_PEARL);

        if (!hasPearl) return;

        boolean canEnter = checkPlayerWallPhase(client);

        String message = canEnter ? "МОЖНО!" : "НЕЛЬЗЯ!";
        int color = canEnter ? 0xFF22FF22 : 0xFFFF2222;

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        int textWidth = client.textRenderer.getWidth(message);
        int x = (screenWidth - textWidth) / 2;
        int y = (screenHeight / 2) + 12;

        context.drawTextWithShadow(client.textRenderer, Text.literal(message), x, y, color);
    }

    private static boolean checkPlayerWallPhase(MinecraftClient client) {
        PlayerEntity player = client.player;
        World world = client.world;
        if (player == null || world == null) return false;

        // Позиция камеры броска
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

        BlockHitResult finalHit = null;
        Vec3d simPos = throwPos;

        for (int step = 0; step < 80; step++) {
            Vec3d nextPos = simPos.add(velocity);

            BlockHitResult hit = world.raycast(new RaycastContext(
                    simPos,
                    nextPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    player
            ));

            if (hit.getType() != HitResult.Type.MISS) {
                finalHit = hit;
                break;
            }

            simPos = nextPos;
            velocity = velocity.multiply(0.99D).subtract(0.0, 0.03D, 0.0);
        }

        if (finalHit == null) {
            return false;
        }

        Vec3d hitPos = finalHit.getPos();
        BlockPos targetBlock = finalHit.getBlockPos();
        Direction hitSide = finalHit.getSide();

        // 1. УЧЁТ ПОЗИЦИИ ИГРОКА:
        // Клип в блоки невозможен, если игрок не стоит в упор к стене.
        // Расстояние от горизонтального центра хитбокса игрока до точки удара должно быть <= 1.25 блока
        double horizontalDistSq = MathHelper.square(player.getX() - hitPos.x) + MathHelper.square(player.getZ() - hitPos.z);
        if (horizontalDistSq > 1.6D) {
            return false;
        }

        // Вертикальное расстояние от ног игрока до точки удара
        double relativeHitY = hitPos.y - player.getY();
        // Удар должен приходиться примерно в диапазон тела игрока (от пола до потолка над головой)
        if (relativeHitY < -0.2D || relativeHitY > 2.5D) {
            return false;
        }

        BlockState hitState = world.getBlockState(targetBlock);
        if (hitState.isAir() || !hitState.getFluidState().isEmpty()) {
            return false;
        }

        // 2. Проверяем, застрянет ли хитбокс игрока в блоках после телепортации
        // Высота хитбокса игрока (1.5 при шифте, 1.8 стоя)
        double playerHeight = player.isSneaking() ? 1.5D : 1.8D;
        double halfWidth = 0.3D;

        // Позиция, куда ванильный сервер приземлит игрока (со сдвигом от нормали грани)
        Vec3d landingPos = hitPos.add(Vec3d.of(hitSide.getVector()).multiply(0.05D));
        // Низ хитбокса после тп
        double landingBottomY = (hitSide == Direction.UP) ? hitPos.y : (hitPos.y - (playerHeight * 0.8D));

        Box landingBox = new Box(
                landingPos.x - halfWidth, landingBottomY, landingPos.z - halfWidth,
                landingPos.x + halfWidth, landingBottomY + playerHeight, landingPos.z + halfWidth
        );

        // Считаем, сколько твёрдых блоков перекрывает голова/тело игрока в точке приземления
        int solidCollisions = 0;
        BlockPos minB = BlockPos.ofFloored(landingBox.minX + 0.01, landingBox.minY + 0.01, landingBox.minZ + 0.01);
        BlockPos maxB = BlockPos.ofFloored(landingBox.maxX - 0.01, landingBox.maxY - 0.01, landingBox.maxZ - 0.01);

        for (int x = minB.getX(); x <= maxB.getX(); x++) {
            for (int y = minB.getY(); y <= maxB.getY(); y++) {
                for (int z = minB.getZ(); z <= maxB.getZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = world.getBlockState(p);
                    if (s.isOpaqueFullCube() && s.blocksMovement()) {
                        solidCollisions++;
                    }
                }
            }
        }

        // Дроби координат точки удара внутри блока [0.0 ... 1.0]
        double fracY = hitPos.y - Math.floor(hitPos.y);

        // 3. Условия успешного захода:
        // Ситуация со скриншота 1: бросок в верхний край стыка потолка (fracY > 0.80) при наличии потолка сверху
        boolean isCeilingSeam = hitSide.getAxis().isHorizontal() && fracY >= 0.80D && isSolid(world, targetBlock.up());
        
        // Бросок в потолок вплотную к стене
        boolean isCeilingCorner = (hitSide == Direction.DOWN) && (
                isSolid(world, targetBlock.north()) || isSolid(world, targetBlock.south()) ||
                isSolid(world, targetBlock.west()) || isSolid(world, targetBlock.east())
        );

        // Если при телепортации модель игрока врезается в верхний блок потолка или застревает в стыке
        if (isCeilingSeam || isCeilingCorner) {
            return true;
        }

        // Если бросок в центр вертикальной стены (скриншот 2), но нет застревания в потолке — клип не сработает
        if (hitSide.getAxis().isHorizontal() && fracY < 0.80D && fracY > 0.20D) {
            return false;
        }

        // Общее правило: застрял ли игрок в твердых блоках головой/туловищем при нахождении в упор
        return solidCollisions >= 1;
    }

    private static boolean isSolid(World world, BlockPos pos) {
        BlockState s = world.getBlockState(pos);
        return !s.isAir() && s.getFluidState().isEmpty() && s.isOpaqueFullCube();
    }
}
