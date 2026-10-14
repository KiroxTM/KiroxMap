# KiroxMap: contexto del proyecto y del core (cubiomes)

Documento para pegar en otros chats o prompts cuando haga falta contexto: versiones, cómo funciona el core,
qué funciones se usan y cómo actualizarlo. Estado de referencia: **KiroxMap 1.0.1 + relieve del terreno**, 2026-10-14.
Todo lo de este estado compiló y funcionó en el equipo del autor (Windows, PowerShell, `./gradlew build`).

---

## 1. Resumen

- **Qué es:** mod de mapa a pantalla completa (tecla **M**) que dibuja biomas y estructuras **solo a partir de la semilla del mundo**.
  Nunca carga chunks ni lee bloques. **Solo cliente.**
- **Loaders:** Fabric y NeoForge, mismo código en el módulo `common/`.
- **Autor / licencia del mod:** Kirox, CC0-1.0.
- **Idiomas del mod:** `en_us.json` y `es_es.json` en `common/src/main/resources/assets/kirox_map/lang/`.

## 2. Versiones (de `gradle.properties` y de la salida de Gradle)

| Pieza | Versión |
|---|---|
| Minecraft | 26.3 (rango `[26.3, 26.4)`) |
| Java | 25 (toolchain y `options.release`) |
| Gradle | 9.7.1 |
| Fabric Loom | 1.18.4 |
| Fabric Loader / API | 0.19.5 / 0.162.0+26.3 |
| NeoForge | 26.3.0.43-beta (mínimo 26.3.0.0-beta) |
| NeoForm | 26.3-1 |
| **cubiomes (bindings)** | `0.0.0-2026.9.22.1790089484+18edd56575a60fe7129705bf972de0a511437527` |
| Versión del mod | 1.0.1 |

`mod_id=kirox_map` (idéntico en `fabric.mod.json`, `neoforge.mods.toml` y `KiroxMap.MOD_ID`; NeoForge no admite `-`).
Si se añade un campo a `gradle.properties` que usen los recursos, hay que añadirlo también a `expandProps` en
`build-logic/src/main/groovy/multiloader-common.gradle`.

---

## 3. El core: cubiomes

### 3.1 Qué son los repositorios (esto confunde)

| Qué | Dónde | Para qué |
|---|---|---|
| cubiomes original | `github.com/Cubitect/cubiomes` | Librería en **C** de Cubitect (MIT). Base de todo. Se queda atrás en versiones nuevas de Minecraft. |
| **fork de xpple, rama `master`** | `github.com/xpple/cubiomes` | El mismo C, **mantenido y actualizado** (soporte de versiones nuevas, terreno 1.18+, etc.). |
| **fork de xpple, rama `java-bindings`** | `github.com/xpple/cubiomes/tree/java-bindings` | **Envoltura Java** (FFM, generada con jextract) de la rama `master`, con la librería nativa compilada dentro. Se actualiza sola con cada commit de `master`. |
| Artefacto Maven | `dev.xpple:cubiomes` | Lo que descarga Gradle: la rama `java-bindings` publicada. |

**El mod ya usa el fork de xpple.** No hay "otro core" al que migrar: `master` es el C, `java-bindings` es el Java que se usa.

### 3.2 Dónde se publica y cómo es la versión

- Repositorio Maven: `https://maven.xpple.dev/maven2/` (declarado en `multiloader-common.gradle`, solo para el grupo `dev.xpple`).
  También aparece en Maven Central.
- Formato de la versión: `0.0.0-<AAAA.M.D>.<segundos epoch>+<commit de master>`.
- **El número de segundos es la hora a la que el CI compiló el paquete**, no la del commit. **No se puede calcular**:
  hay que copiar el nombre exacto de la carpeta en el repositorio Maven (abrirlo en el navegador).
  Un intento de adivinarlo falló con `Could not find dev.xpple:cubiomes:...`.
- **Pendiente / opcional:** hay una build más nueva, **2026-10-02, commit `4f04235f3e6e25491a02a830e4f500f68045be17`**
  (añade soporte ARM en Linux y Windows). La API que usa el mod no cambia entre las dos (solo cambió `CubiomesInit.load()`).
  Su nombre exacto no se ha podido ver desde fuera: buscar la carpeta que empiece por `0.0.0-2026.10.2` y termine en `+4f04235f…`.

### 3.3 Requisitos de los bindings

- **JDK 23 o superior** (usan la API Foreign Function & Memory; el proyecto usa Java 25, así que no hay problema).
- Hay que llamar a `CubiomesInit.load()` antes de usar nada. En el mod lo hace `world/NativeLib.java`
  (que además carga la librería nativa con el nombre correcto según plataforma).
- La dependencia se mete en el jar: `include(implementation(...))` en `fabric/build.gradle` (jar-in-jar de Loom) y
  `jarJar(implementation(...))` en `neoforge/build.gradle`. NeoForge necesita además un ajuste para el acceso FFM nativo
  (comentado en su `build.gradle`).
- Licencias: bindings LGPL-3.0; cubiomes (Cubitect) MIT; el código propio del mod CC0-1.0.

### 3.4 Cómo ver la API real (sin tener el jar)

Los bindings son código Java generado y **está en el repositorio**, así que se puede leer:

```bash
git clone --depth 1 --branch java-bindings https://github.com/xpple/cubiomes
# funciones:   src/main/java/dev/xpple/cubiomes/Cubiomes.java, Cubiomes_1.java, Cubiomes$shared.java
# structs:     src/main/java/dev/xpple/cubiomes/Generator.java, Range.java, Pos.java, SurfaceNoise.java, TerrainNoise.java ...
git clone --depth 1 https://github.com/xpple/cubiomes     # el C (master): generator.c, finders.c, biomenoise.c, terrainnoise.c
```

- Con el jar ya descargado: `javap -public -cp <jar> dev.xpple.cubiomes.Cubiomes`
  (el jar está en `~/.gradle/caches/modules-2/files-2.1/dev.xpple/cubiomes/`).
- Nota para asistentes: la API de la web de GitHub se limita por uso (rate limit) y las páginas HTML pueden bloquear
  accesos automáticos; `git clone` por HTTPS funciona.
- Convención de los nombres: la función C `foo(...)` es `Cubiomes.foo(...)` en Java; los punteros son `MemorySegment`
  (`MemorySegment.NULL` para `NULL`); los structs tienen `allocate()` y accesores `Struct.campo(segmento)`.

### 3.5 Lo que usa el mod hoy

Funciones y structs (sacado del código fuente):

- Arranque y generador: `CubiomesInit.load`, `Generator.allocate`, `Cubiomes.setupGenerator`, `applySeed`, `getDimension`,
  `isOverworld`, `MC_26_3`, `DIM_OVERWORLD` / `DIM_NETHER` / `DIM_END`.
- Biomas: `Range.allocate` (+ `scale`, `x`, `y`, `z`, `sx`, `sy`, `sz`), `genBiomes`, `getMinCacheSize`, `getBiomeAt`,
  `biome2str`, `initBiomeColors`.
- Estructuras: `getStructureConfig` (`StructureConfig`), `getStructurePos` (`Pos`), `isViableStructurePos`,
  `isViableStructureTerrain`, `isViableEndCityTerrain`, `END_SHIP`, `End_City`, `getEndCityPieces` (`Piece`, `END_CITY_PIECES_MAX`).
- Strongholds: `StrongholdIter.allocate`, `initFirstStronghold`, `nextStronghold`.
- Ruido de superficie del End: `SurfaceNoise.allocate`, `initSurfaceNoise`.
- **Relieve (nuevo):** `Cubiomes.mapApproxHeight`, `Cubiomes.mapEndSurfaceHeight`, `Generator.en(...)`.

Estructuras del mod (`world/StructureKind.java`): village, stronghold, pillager outpost, ocean monument, woodland mansion,
ruined portal, desert pyramid, jungle temple, swamp hut, igloo, shipwreck, ocean ruin, buried treasure, ancient city,
trail ruins, trial chambers, abandoned camp, mineshaft; Nether: fortress, bastion remnant, ruined portal; End: end city,
end city ship, end gateway.

### 3.6 Límites del core (decisiones ya tomadas)

- Todo sale de la **semilla**: no hay terreno real. Las posiciones de estructuras y las alturas pueden diferir un poco del juego.
- **Altura (Y) de estructuras: no disponible** (el índice solo guarda X y Z); por eso al copiar coordenadas no se copia Y.
- **Relieve:** alturas **aproximadas**. Overworld: `mapApproxHeight` (parámetro de clima "depth", una muestra cada 4 bloques;
  en tiles más lejanos, una muestra por texel). End: `mapEndSurfaceHeight`, solo al zoom más cercano (4 bloques/texel).
  Nether: cubiomes no da alturas, sin relieve.
- Cubiomes también tiene generación de terreno más exacta (`TerrainNoise`, `samplePreliminarySurfaceLevel`, `getSurfaceHeight`),
  más cara de calcular: sería el siguiente paso si se quiere un relieve más fiel. No está usada.

---

## 4. Arquitectura del mod (`common/src/main/java/com/kiroxtm/`)

| Paquete | Archivos clave | Responsabilidad |
|---|---|---|
| `world/` | `NativeLib`, `SeedWorld`, `StructureIndex`, `StructureKind`, `Dim`, `BiomeData`, `SlimeChunks` | Único sitio que habla con cubiomes: biomas, estructuras, strongholds, alturas, slime chunks. |
| `map/` | `TileCache`, `TileKey`, `MapView`, `StructureLayer`, `DiskCache`, `Relief` | Tiles de 128x128 texels en escalas 4, 16, 64 y 256 bloques/texel, generados en segundo plano; caché en disco; relieve. |
| `screen/` | `AtlasScreen`, `AtlasSession`, `MapCanvas`, `SidePanel`, `WaypointScreen`, `IconButton`, `ToolIcon`, `Icons`, `KiroxConfigScreen` | Interfaz: barra de botones, lienzo del mapa, panel lateral, waypoints. |
| `config/` | `AtlasConfig`, `Waypoint` | Ajustes y waypoints. |
| `client/` | `KiroxClient`, `KiroxKeys`, `ErrorLog` | Arranque, tecla M, registro de errores. |
| `platform/` | `Services` | Puente a Fabric/NeoForge. |

Zoom: `MapView.DEFAULT_ZOOM = 2`, `MAX_BLOCKS_PER_PIXEL = 8`. Los iconos del mapa se reducen linealmente hasta el 25% al
alejar desde el zoom base. Hay una comprobación de capas (`checkLayering`) que impide que `common` use cosas de un loader.

### Relieve (`map/Relief.java`, matemática pura y con tests)

- Entrada: alturas de (128+2)x(128+2) muestras (un texel de borde, para que los tiles vecinos coincidan).
- Salida: un `byte` con signo por texel (-127 oscuro, 0 plano, +127 claro), con luz desde el noroeste.
- Se guarda aparte del color del bioma, así sobrevive a los cambios de paleta.
- Ajustes: `GAIN` (fuerza), `MAX_DARKEN` / `MAX_LIGHTEN`, `WATER_FACTOR` (mar más suave), `strength(scale)` (fuerza por zoom:
  4 y 16 = 1.0, 64 = 0.7, 256 = 0.35).

### Caché en disco (`map/DiskCache.java`)

- Carpeta `kirox-map-cache/<semilla>/<dimensión>/` junto a la carpeta de config; límite `MAX_BYTES` = 512 MB.
- Archivos: `t<escala>_<tx>_<tz>.z` (biomas), `r<escala>_<tx>_<tz>.z` (relieve), `s_<estructura>.z`, `strongholds.z`.
- **`FORMAT` hay que subirlo siempre que cambie el core o lo que se guarda** (ahora `2`); borra las cachés viejas una vez.
- Se desactiva con `"diskCache": false` en `config/kirox-map.json`.

### Ajustes (`config/kirox-map.json`)

`showGrid`, `showSlimeChunks`, `showLabels`, `showWaypointLabels`, `showRelief` (por defecto `true`), `diskCache`,
`uiScale` (1, 2 o 3; 3 = tamaño original), `panelOpen`, `showHelp` (por defecto `false`), `panelTab`, más estructuras y biomas
activos y waypoints.

### Iconos de la interfaz

PNG blancos de 64x64 en `assets/kirox_map/textures/gui/icons/<nombre>.png` (+ `.png.mcmeta` con `blur: true`), dibujados
escalados por `ToolIcon`. Los genera `tools/generate_icons.py`: `python3 tools/generate_icons.py relief` regenera solo ese
(los demás, como `scale` y `regen`, se pueden haber sustituido a mano). Para un icono nuevo: añadir su constante en `ToolIcon`
y su PNG con el mismo nombre en minúsculas.

---

## 5. Checklist: actualizar el core o Minecraft

1. Elegir la build en `https://maven.xpple.dev/maven2/dev/xpple/cubiomes/` y copiar el nombre **exacto** a `cubiomes_version`.
2. Si sube Minecraft: actualizar `minecraft_version`, `minecraft_version_range`, `neo_form_version`, versiones de Fabric y NeoForge,
   y comprobar que existe la constante `Cubiomes.MC_<versión>()` en los bindings (ahora se usa `MC_26_3`).
3. Subir `DiskCache.FORMAT`.
4. Comparar la API usada (sección 3.5) con los bindings nuevos: `git diff <commit viejo> <commit nuevo> -- src/main/java` en la rama
   `java-bindings` (en la actualización a `4f04235` solo cambió `CubiomesInit.java`).
5. Revisar que `StructureKind` sigue al día (estructuras nuevas del juego) y que `NativeLib` sigue cargando bien en Windows, Linux y macOS.
6. `./gradlew build` (compila los dos loaders y pasa los tests y la comprobación de capas); probar con `./gradlew :fabric:runClient`
   y `:neoforge:runClient`.
7. Anotar el cambio en `REVERT_NOTES.md` (lleva una sección por cambio con archivos tocados y cómo revertirlo).

## 6. Comandos útiles

```bash
./gradlew build                # los dos loaders + tests
./gradlew :fabric:build        # solo Fabric
./gradlew :neoforge:build      # solo NeoForge
./gradlew :fabric:runClient    # probar en Fabric
./gradlew :neoforge:runClient  # probar en NeoForge
```

Los jars quedan en `fabric/build/libs/` y `neoforge/build/libs/` (los que no llevan `-sources` ni `-javadoc`).

## 7. Notas para asistentes (Claude u otros)

- En el entorno del asistente no hay JDK ni las librerías de Minecraft: **no se puede compilar el mod ahí**. Lo que se entrega
  hay que compilarlo en el equipo del autor; si algo falla, pedir el error de Gradle.
- No inventar nombres de la API de Minecraft 26.x ni de cubiomes: comprobar los de cubiomes en los bindings (sección 3.4) y los de
  Minecraft en el código existente del mod, que ya compila.
- El autor escribe en español; el README y el código están en inglés.
- Historial de cambios y cómo revertirlos: `REVERT_NOTES.md`.
