package net.montoyo.wd.utilities.data;

import net.minecraft.core.Direction;
import org.joml.Vector3d;
import org.joml.Vector3f;

public enum BlockSide {
    BOTTOM(0, Direction.DOWN,  new Vector3d(0, -1, 0),  new Vector3d(1, 0, 0),  new Vector3d(0, 0, 1)),
    TOP(1,    Direction.UP,    new Vector3d(0, 1, 0),   new Vector3d(1, 0, 0),  new Vector3d(0, 0, -1)),
    NORTH(2,  Direction.NORTH, new Vector3d(0, 0, -1),  new Vector3d(-1, 0, 0), new Vector3d(0, 1, 0)),
    SOUTH(3,  Direction.SOUTH, new Vector3d(0, 0, 1),   new Vector3d(1, 0, 0),  new Vector3d(0, 1, 0)),
    WEST(4,   Direction.WEST,  new Vector3d(-1, 0, 0),  new Vector3d(0, 0, -1), new Vector3d(0, 1, 0)),
    EAST(5,   Direction.EAST,  new Vector3d(1, 0, 0),   new Vector3d(0, 0, 1),  new Vector3d(0, 1, 0));

    public final int id;
    public final Direction direction;
    public final Vector3d normal;
    public final Vector3d right;
    public final Vector3d up;

    BlockSide(int id, Direction direction, Vector3d normal, Vector3d right, Vector3d up) {
        this.id = id;
        this.direction = direction;
        this.normal = normal;
        this.right = right;
        this.up = up;
    }

    public static BlockSide fromInt(int val) {
        if (val >= 0 && val < values().length) {
            return values()[val];
        }
        return BOTTOM;
    }

    public static BlockSide fromDirection(Direction dir) {
        return switch (dir) {
            case DOWN -> BOTTOM;
            case UP -> TOP;
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
        };
    }

    public BlockSide opposite() {
        return switch (this) {
            case BOTTOM -> TOP;
            case TOP -> BOTTOM;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
        };
    }

    public Vector3d rightCrossUp() {
        return new Vector3d(right).cross(up);
    }

    public boolean isHorizontal() {
        return this == NORTH || this == SOUTH || this == WEST || this == EAST;
    }
}