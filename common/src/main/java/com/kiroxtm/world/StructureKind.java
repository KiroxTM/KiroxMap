package com.kiroxtm.world;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntSupplier;

import dev.xpple.cubiomes.Cubiomes;

/** The structures the atlas can predict, each with its cubiomes type and home dimension. */
public enum StructureKind {
	VILLAGE("village", Dim.OVERWORLD, Cubiomes::Village),
	STRONGHOLD("stronghold", Dim.OVERWORLD, Cubiomes::Stronghold),
	PILLAGER_OUTPOST("pillager_outpost", Dim.OVERWORLD, Cubiomes::Outpost),
	OCEAN_MONUMENT("ocean_monument", Dim.OVERWORLD, Cubiomes::Monument),
	WOODLAND_MANSION("woodland_mansion", Dim.OVERWORLD, Cubiomes::Mansion),
	RUINED_PORTAL("ruined_portal", Dim.OVERWORLD, Cubiomes::Ruined_Portal),
	DESERT_PYRAMID("desert_pyramid", Dim.OVERWORLD, Cubiomes::Desert_Pyramid),
	JUNGLE_TEMPLE("jungle_temple", Dim.OVERWORLD, Cubiomes::Jungle_Temple),
	SWAMP_HUT("swamp_hut", Dim.OVERWORLD, Cubiomes::Swamp_Hut),
	IGLOO("igloo", Dim.OVERWORLD, Cubiomes::Igloo),
	SHIPWRECK("shipwreck", Dim.OVERWORLD, Cubiomes::Shipwreck),
	OCEAN_RUIN("ocean_ruin", Dim.OVERWORLD, Cubiomes::Ocean_Ruin),
	BURIED_TREASURE("buried_treasure", Dim.OVERWORLD, Cubiomes::Treasure),
	ANCIENT_CITY("ancient_city", Dim.OVERWORLD, Cubiomes::Ancient_City),
	TRAIL_RUINS("trail_ruins", Dim.OVERWORLD, Cubiomes::Trail_Ruins),
	TRIAL_CHAMBERS("trial_chambers", Dim.OVERWORLD, Cubiomes::Trial_Chambers),
	ABANDONED_CAMP("abandoned_camp", Dim.OVERWORLD, Cubiomes::Abandoned_Camp),
	MINESHAFT("mineshaft", Dim.OVERWORLD, Cubiomes::Mineshaft),
	NETHER_FORTRESS("nether_fortress", Dim.NETHER, Cubiomes::Fortress),
	BASTION_REMNANT("bastion_remnant", Dim.NETHER, Cubiomes::Bastion),
	RUINED_PORTAL_NETHER("ruined_portal_nether", Dim.NETHER, Cubiomes::Ruined_Portal_N),
	END_CITY("end_city", Dim.END, Cubiomes::End_City),
	/** End cities that have a ship; {@link #END_CITY} lists the ones without. */
	END_CITY_SHIP("end_city_ship", Dim.END, Cubiomes::End_City),
	END_GATEWAY("end_gateway", Dim.END, Cubiomes::End_Gateway);

	private final String key;
	private final Dim dim;
	private final IntSupplier cubiomesType;

	StructureKind(String key, Dim dim, IntSupplier cubiomesType) {
		this.key = key;
		this.dim = dim;
		this.cubiomesType = cubiomesType;
	}

	/** Stable id used in the config file and in translation keys. */
	public String key() {
		return key;
	}

	public Dim dim() {
		return dim;
	}

	public int cubiomesType() {
		return cubiomesType.getAsInt();
	}

	public static List<StructureKind> in(Dim dim) {
		return Arrays.stream(values()).filter(kind -> kind.dim == dim).toList();
	}
}
