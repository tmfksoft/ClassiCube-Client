package com.mojang.minecraft.level.tile;

import com.mojang.minecraft.level.Level;

public final class SlabBlock extends Block {

    private boolean doubleSlab;

    public SlabBlock(int var1, boolean var2) {
        super(var1, 6);
        doubleSlab = var2;
        if (!var2) {
            setBounds(0F, 0F, 0F, 1F, 0.5F, 1F);
        }

    }

    @Override
    public final boolean canRenderSide(Level level, int x, int y, int z, int side) {
        if (this != SLAB) {
            super.canRenderSide(level, x, y, z, side);
        }

        return side == 1 || (super.canRenderSide(level, x, y, z, side) &&
                (side == 0 || level.getTile(x, y, z) != id));
    }

    @Override
    public final int getDrop() {
        return SLAB.id;
    }

    @Override
    public final int getTextureId(int texture) {
        return texture <= 1 ? 6 : 5;
    }

    @Override
    public final boolean isCube() {
        return doubleSlab;
    }

    @Override
    public final boolean isSolid() {
        return doubleSlab;
    }

    @Override
    public final void onAdded(Level level, int x, int y, int z) {
        if (this != SLAB) {
            super.onAdded(level, x, y, z);
        }

        if (level.getTile(x, y - 1, z) == SLAB.id) {
            level.setTile(x, y, z, 0);
            level.setTile(x, y - 1, z, DOUBLE_SLAB.id);
        }

    }

    @Override
    public final void onNeighborChange(Level level, int x, int y, int z, int side) {
        if (this == SLAB) {
            ;
        }
    }
}
