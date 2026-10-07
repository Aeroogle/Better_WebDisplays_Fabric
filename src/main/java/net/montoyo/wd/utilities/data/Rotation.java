package net.montoyo.wd.utilities.data;

public enum Rotation {
    ROT_0(0),
    ROT_90(1),
    ROT_180(2),
    ROT_270(3);

    public final int id;

    Rotation(int id) {
        this.id = id;
    }

    public static Rotation fromInt(int val) {
        if (val >= 0 && val < values().length) {
            return values()[val];
        }
        return ROT_0;
    }

    public Rotation next() {
        return values()[(id + 1) % values().length];
    }
}