package net.montoyo.wd.utilities.math;

import net.minecraft.core.BlockPos;

public class Vector3i {
    public int x;
    public int y;
    public int z;

    public Vector3i() {
        this.x = 0;
        this.y = 0;
        this.z = 0;
    }

    public Vector3i(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3i(Vector3i other) {
        this.x = other.x;
        this.y = other.y;
        this.z = other.z;
    }

    public Vector3i(BlockPos pos) {
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
    }

    public void set(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public BlockPos toBlock() {
        return new BlockPos(x, y, z);
    }

    @Override
    public String toString() {
        return "Vector3i(" + x + ", " + y + ", " + z + ")";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Vector3i other)) return false;
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * x + y) + z;
    }
}