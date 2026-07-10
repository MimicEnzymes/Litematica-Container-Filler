package com.mimicenzymes.litematicafiller.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class InteractionTargeting {
    private static final double HIT_INSET = 1.0E-4D;
    private static final double INTERACTION_RANGE_TOLERANCE = 0.125D;

    private InteractionTargeting() {
    }

    public static BlockHitResult createBlockHitResult(MinecraftClient client, BlockPos pos) {
        Vec3d origin = client.player != null ? client.player.getEyePos() : Vec3d.ofCenter(pos);
        Direction side = closestSide(origin, pos);
        return new BlockHitResult(hitPointOnSide(origin, pos, side), side, pos, false);
    }

    public static double squaredInteractionRange(double reach) {
        double effectiveReach = Math.max(0.0D, reach) + INTERACTION_RANGE_TOLERANCE;
        return effectiveReach * effectiveReach;
    }

    public static double squaredDistanceToBlock(Vec3d origin, BlockPos pos) {
        return origin.squaredDistanceTo(closestPointInBlock(origin, pos));
    }

    private static Vec3d closestPointInBlock(Vec3d origin, BlockPos pos) {
        double minX = pos.getX();
        double minY = pos.getY();
        double minZ = pos.getZ();
        double maxX = minX + 1.0D;
        double maxY = minY + 1.0D;
        double maxZ = minZ + 1.0D;
        return new Vec3d(
                clamp(origin.x, minX, maxX),
                clamp(origin.y, minY, maxY),
                clamp(origin.z, minZ, maxZ)
        );
    }

    private static Vec3d hitPointOnSide(Vec3d origin, BlockPos pos, Direction side) {
        double minX = pos.getX();
        double minY = pos.getY();
        double minZ = pos.getZ();
        double maxX = minX + 1.0D;
        double maxY = minY + 1.0D;
        double maxZ = minZ + 1.0D;
        double x = clamp(origin.x, minX + HIT_INSET, maxX - HIT_INSET);
        double y = clamp(origin.y, minY + HIT_INSET, maxY - HIT_INSET);
        double z = clamp(origin.z, minZ + HIT_INSET, maxZ - HIT_INSET);

        return switch (side) {
            case DOWN -> new Vec3d(x, minY, z);
            case UP -> new Vec3d(x, maxY, z);
            case NORTH -> new Vec3d(x, y, minZ);
            case SOUTH -> new Vec3d(x, y, maxZ);
            case WEST -> new Vec3d(minX, y, z);
            case EAST -> new Vec3d(maxX, y, z);
        };
    }

    private static Direction closestSide(Vec3d origin, BlockPos pos) {
        double minX = pos.getX();
        double minY = pos.getY();
        double minZ = pos.getZ();
        double maxX = minX + 1.0D;
        double maxY = minY + 1.0D;
        double maxZ = minZ + 1.0D;

        Direction side = Direction.UP;
        double farthestOutside = 0.0D;
        double west = minX - origin.x;
        if (west > farthestOutside) {
            farthestOutside = west;
            side = Direction.WEST;
        }
        double east = origin.x - maxX;
        if (east > farthestOutside) {
            farthestOutside = east;
            side = Direction.EAST;
        }
        double down = minY - origin.y;
        if (down > farthestOutside) {
            farthestOutside = down;
            side = Direction.DOWN;
        }
        double up = origin.y - maxY;
        if (up > farthestOutside) {
            farthestOutside = up;
            side = Direction.UP;
        }
        double north = minZ - origin.z;
        if (north > farthestOutside) {
            farthestOutside = north;
            side = Direction.NORTH;
        }
        double south = origin.z - maxZ;
        if (south > farthestOutside) {
            farthestOutside = south;
            side = Direction.SOUTH;
        }

        if (farthestOutside > 0.0D) {
            return side;
        }

        double nearestInside = origin.y - minY;
        side = Direction.DOWN;
        double toUp = maxY - origin.y;
        if (toUp < nearestInside) {
            nearestInside = toUp;
            side = Direction.UP;
        }
        double toWest = origin.x - minX;
        if (toWest < nearestInside) {
            nearestInside = toWest;
            side = Direction.WEST;
        }
        double toEast = maxX - origin.x;
        if (toEast < nearestInside) {
            nearestInside = toEast;
            side = Direction.EAST;
        }
        double toNorth = origin.z - minZ;
        if (toNorth < nearestInside) {
            nearestInside = toNorth;
            side = Direction.NORTH;
        }
        double toSouth = maxZ - origin.z;
        if (toSouth < nearestInside) {
            side = Direction.SOUTH;
        }
        return side;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
