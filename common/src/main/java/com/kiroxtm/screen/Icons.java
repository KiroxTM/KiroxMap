package com.kiroxtm.screen;

import java.util.EnumMap;
import java.util.Map;

import com.kiroxtm.world.StructureKind;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The vanilla item drawn as the marker of each structure kind. */
final class Icons {
	private static final Map<StructureKind, ItemStack> ICONS = build();

	private Icons() {
	}

	static ItemStack of(StructureKind kind) {
		return ICONS.get(kind);
	}

	private static Map<StructureKind, ItemStack> build() {
		Map<StructureKind, ItemStack> icons = new EnumMap<>(StructureKind.class);
		icons.put(StructureKind.VILLAGE, new ItemStack(Items.BELL));
		icons.put(StructureKind.STRONGHOLD, new ItemStack(Items.ENDER_EYE));
		icons.put(StructureKind.PILLAGER_OUTPOST, new ItemStack(Items.CROSSBOW));
		icons.put(StructureKind.OCEAN_MONUMENT, new ItemStack(Items.PRISMARINE));
		icons.put(StructureKind.WOODLAND_MANSION, new ItemStack(Items.TOTEM_OF_UNDYING));
		icons.put(StructureKind.RUINED_PORTAL, new ItemStack(Items.CRYING_OBSIDIAN));
		icons.put(StructureKind.DESERT_PYRAMID, new ItemStack(Items.CHISELED_SANDSTONE));
		icons.put(StructureKind.JUNGLE_TEMPLE, new ItemStack(Items.MOSSY_COBBLESTONE));
		icons.put(StructureKind.SWAMP_HUT, new ItemStack(Items.CAULDRON));
		icons.put(StructureKind.IGLOO, new ItemStack(Items.SNOW_BLOCK));
		icons.put(StructureKind.SHIPWRECK, new ItemStack(Items.OAK_BOAT));
		icons.put(StructureKind.OCEAN_RUIN, new ItemStack(Items.CRACKED_STONE_BRICKS));
		icons.put(StructureKind.BURIED_TREASURE, new ItemStack(Items.HEART_OF_THE_SEA));
		icons.put(StructureKind.ANCIENT_CITY, new ItemStack(Items.ECHO_SHARD));
		icons.put(StructureKind.TRAIL_RUINS, new ItemStack(Items.BRUSH));
		icons.put(StructureKind.TRIAL_CHAMBERS, new ItemStack(Items.TRIAL_KEY));
		icons.put(StructureKind.ABANDONED_CAMP, new ItemStack(Items.CAMPFIRE));
		icons.put(StructureKind.MINESHAFT, new ItemStack(Items.RAIL));
		icons.put(StructureKind.NETHER_FORTRESS, new ItemStack(Items.NETHER_BRICKS));
		icons.put(StructureKind.BASTION_REMNANT, new ItemStack(Items.GILDED_BLACKSTONE));
		icons.put(StructureKind.RUINED_PORTAL_NETHER, new ItemStack(Items.CRYING_OBSIDIAN));
		icons.put(StructureKind.END_CITY, new ItemStack(Items.PURPUR_BLOCK));
		icons.put(StructureKind.END_CITY_SHIP, new ItemStack(Items.ELYTRA));
		icons.put(StructureKind.END_GATEWAY, new ItemStack(Items.ENDER_PEARL));
		return icons;
	}
}
