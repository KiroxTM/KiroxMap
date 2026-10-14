<p align="center">
  <img src="public/banners/1.png" alt="Kirox Map" width="100%">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-26.3-62b47a?style=flat-square" alt="Minecraft 26.3">
  <img src="https://img.shields.io/badge/Fabric-supported-dbd0b4?style=flat-square" alt="Fabric">
  <img src="https://img.shields.io/badge/NeoForge-supported-d7803b?style=flat-square" alt="NeoForge">
  <img src="https://img.shields.io/badge/Java-25-e76f00?style=flat-square" alt="Java 25">
  <img src="https://img.shields.io/badge/side-client--only-4c8dd6?style=flat-square" alt="Client-side only">
  <img src="https://img.shields.io/badge/license-CC0--1.0-lightgrey?style=flat-square" alt="CC0-1.0">
</p>

**KiroxMap 1.0.1-Beta** is a full-screen world map for Minecraft that works **only from the world seed.** **Press M** and see the biomes and structures of the whole world, including places you have never visited. It never loads chunks and never reads the blocks around you.

> **Client-side only.** Fabric and NeoForge, Minecraft 26.3.

<p align="center">
  <img src="public/covers/1.png" alt="Overworld map with biomes and structures" width="100%">
</p>

> **Author:** This is my **first mod**, and I plan to keep it actively updated by improving performance, fixing bugs and adding support for new versions of Minecraft.
>
> If you run into any issue or have a suggestion, please [open an issue](../../issues). Forks and contributions are welcome: feel free to create your own fork, help me find and fix bugs, or send a pull request.
>
> **Thank you for your support!**

---

<img src="public/banners/2.png" alt="All three dimensions" width="100%">

## Overworld, Nether and End

Switch dimensions from the toolbar and explore each one:

- **Overworld:** every biome, with villages, strongholds, outposts, monuments, mansions, ancient cities, trial chambers, ruined portals and more.
- **Nether:** fortresses, bastions and ruined portals.
- **End:** end cities (marked separately if they have a ship) and end gateways, including the ring of 20 around the main island.

| Overworld | Nether | End |
| :---: | :---: | :---: |
| <img src="public/covers/7.png" alt="Overworld with structure labels"> | <img src="public/covers/8.png" alt="Nether fortresses and bastions"> | <img src="public/covers/9.png" alt="End cities and ships"> |

<p align="center">
  <img src="public/covers/2.png" alt="Nether biomes" width="100%">
</p>

<p align="center">
  <img src="public/covers/3.png" alt="End gateways around the main island" width="100%">
</p>

---

<img src="public/banners/3.png" alt="Structures and biomes" width="100%">

## Structures and biomes

- **Your way:** turn each structure type on or off from the side panel, each with its own icon on the map.
- **Focus:** hide the biomes you do not care about and the rest stand out.
- **Optional layers:** chunk grid, slime chunks, structure labels, waypoint labels and terrain relief.
- **Terrain relief:** hills, mountains and coasts are shaded on top of the biome colors, in the Overworld and (zoomed in) in the End. It is an approximation computed from the seed, not the exact blocks, and the Nether has none. Turn it off with its toolbar button.
- **Details on hover:** hover over anything for its name and coordinates. Right-click to copy coordinates.
- **Fast:** smooth zoom and panning. Tiles are generated in the background and cached on disk, so a world you have already viewed opens instantly.

| Structures panel | Biomes panel |
| :---: | :---: |
| <img src="public/covers/4.png" alt="Structures panel"> | <img src="public/covers/5.png" alt="Biomes panel"> |

<p align="center">
  <img src="public/covers/slime_chunks.png" alt="Slime chunks layer" width="100%">
</p>

---

<img src="public/banners/4.png" alt="Waypoints" width="100%">

## Waypoints

Right-click the map to add a waypoint: pick a name, a color and the exact coordinates. Pins are always visible and the **name only appears when you hover over one**, so the map stays clean.

Edit or delete them at any time, and show or hide them from the waypoints list. They are saved per world and per dimension.

<p align="center">
  <img src="public/covers/6.png" alt="Waypoints list" width="100%">
</p>

---

<img src="public/banners/5.png" alt="Singleplayer and multiplayer" width="100%">

## Seed: automatic or manual

- **Singleplayer:** the seed is detected automatically.
- **Multiplayer:** click the seed button at the top left, type the server's seed once in the box that appears and it is remembered for that server.

The map always shows what the seed generates, not what you have explored. Everything is predicted by [cubiomes](https://github.com/Cubitect/cubiomes) (see [Credits](#credits-and-license)), so in rare cases a structure position or a height can differ slightly from the game.

---

## Installation

1. Install **[Fabric Loader](https://fabricmc.net/use/)** + **[Fabric API](https://modrinth.com/mod/fabric-api)**, or **[NeoForge](https://neoforged.net/)**, for Minecraft 26.3.
2. Drop the mod `.jar` into your `mods` folder.
3. Press **M** in game (changeable in *Controls*).

Requires **Java 25**. No server-side install needed...

## Building from source

Requires **JDK 25**.

```bash
./gradlew build                # both loaders
./gradlew :fabric:build        # Fabric only
./gradlew :neoforge:build      # NeoForge only
./gradlew :fabric:runClient    # test on Fabric
./gradlew :neoforge:runClient  # test on NeoForge
```

The jars are generated in `fabric/build/libs/` and `neoforge/build/libs/` (use the ones without the `-sources` / `-javadoc` suffix).

## Credits and license

Made by **Kirox**.

**World generation core.** All the seed math (biomes, structure placement and the terrain heights behind the relief) comes from **[cubiomes](https://github.com/Cubitect/cubiomes)**, the C library by **Cubitect** (MIT), through its actively maintained fork **[xpple/cubiomes](https://github.com/xpple/cubiomes)** by **xpple**, which keeps it up to date with new Minecraft versions. KiroxMap uses that fork's Java bindings, **[`dev.xpple:cubiomes`](https://github.com/xpple/cubiomes/tree/java-bindings)** (LGPL-3.0), bundled inside the jar together with the native library. This mod would not exist without that work: thank you both.

The mod's own code is licensed under **CC0-1.0**. The bundled cubiomes bindings keep their own licenses.

For maintainers: the versions, the core's API and how to update it are written down in [`CORE_CONTEXT.md`](CORE_CONTEXT.md).
