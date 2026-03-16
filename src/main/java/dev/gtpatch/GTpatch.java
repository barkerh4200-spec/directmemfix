package dev.gtpatch;

import net.minecraftforge.fml.common.Mod;

/**
 * Minimal mod entrypoint required by Forge FML.
 *
 * Without a @Mod-annotated class matching the modId in mods.toml, Forge
 * reports "has mods that were not found" and aborts loading. This class has
 * no logic -- all behaviour is in the Mixin classes in dev.gtpatch.mixin.
 */
@Mod(GTpatch.MODID)
public class GTpatch {
    public static final String MODID = "gtpatch";
}
