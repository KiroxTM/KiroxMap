# Notas de cambios (para poder revertir)

Cambios hechos el 2026-10-14 sobre la versión 1.0.1. Todo el código está en `common/`, así que
aplica a Fabric y NeoForge a la vez.

## A. Caché en disco + botón reGen (el cambio más grande, el que probablemente quieras poder quitar)

Guarda en disco lo que el mapa calcula (ids de bioma de cada tile y posiciones de estructuras) para no
recalcularlo entre sesiones ni al cambiar de dimensión. Carpeta: `<carpeta del juego>/kirox-map-cache/<seed>/`
(tope de 512 MB; borra lo más antiguo). Los waypoints NO se guardan ahí (siguen en `config/kirox-map.json`).

**Desactivar sin tocar código:** en `config/kirox-map.json` poner `"diskCache": false`. Con eso no se lee ni se escribe nada.

**Archivos nuevos**
- `common/.../map/DiskCache.java` (toda la lógica de guardado/lectura/limpieza)
- `common/.../textures/gui/icons/regen.png` y `regen.png.mcmeta` (icono del botón reGen)

**Archivos modificados**
- `map/TileCache.java`: constructor con `DiskCache.Store` y 2 bloques en `generate()` (leer / guardar tile).
- `world/StructureIndex.java`: constructor con `Store`, carga lazy por tipo, `flush()`, strongholds guardados.
- `map/StructureLayer.java`: método `flush()`.
- `screen/AtlasSession.java`: campo `store`, `flush()`, `regenerate()`, abre el store en `show()`.
- `screen/AtlasScreen.java`: botón reGen bajo la seed (`regenerate()`), `colY` más abajo, `session.flush()` en `removed()`.
- `screen/ToolIcon.java`: valor `REGEN`.
- `config/AtlasConfig.java`: campo `diskCache`.
- `lang/en_us.json` y `lang/es_es.json`: claves `kiroxmap.regen` y `kiroxmap.regen.done`.

**Revertir del todo:** usar `KiroxMap_before_cache.zip` (estado justo antes de este cambio), o borrar `DiskCache.java`
y deshacer los puntos de la lista anterior (cada uno es un bloque pequeño y marcado con comentarios).

**Si cambia cubiomes o algo que altere los resultados:** subir `FORMAT` en `DiskCache.java` (borra las cachés viejas
automáticamente) o usar el botón reGen. Un cambio de versión de Minecraft ya invalida la caché sola.

## B. Zoom máximo = 10 bloques/px (primero 12, luego 8; en 1.0.4 pasó a 10: `MapView.MAX_BLOCKS_PER_PIXEL`; la curva de tamaño de iconos termina en 8 en `MapCanvas.SCALE_BPP`)
- `map/MapView.java`: `MAX_BLOCKS_PER_PIXEL = 8`, `MAX_ZOOM` se calcula a partir de él (antes `MAX_ZOOM = 9`, o sea 512 bloques/px).
  Para revertir: `public static final double MAX_ZOOM = 9;`

## C. Tamaño de iconos/textos según zoom (en bloques/px)
- `screen/MapCanvas.java`: `SCALE_BPP` / `SCALE_VALUE` (4 b/px = 100%, 6 = 87.5%, 8 = 62.5%; antes de subirlos un 25%: 100%, 70%, 50%). Texto con mínimo `MIN_LABEL_SCALE = 0.5`.

## D. Parpadeo de iconos de estructuras
- `screen/MapCanvas.java` `drawStructures()`: decisión en coordenadas del mundo, prioridad por `rank()`, dibujo de menos a más importante.
  `COLLISION = 0.8` controla cuánto pueden solaparse (1.0 = como antes, sin solape).
- `map/StructureLayer.java` `Query.contains()`: una búsqueda con más tipos sirve para una vista con menos.

## E. Sonido de UI (clic de botón de Minecraft)
- `IconButton`, `SidePanel` (filas y Todos/Ninguno), `MapCanvas` (menú de clic derecho) y `WaypointScreen` (colores) llaman a
  `playDownSound(Minecraft.getInstance().getSoundManager())`, el mismo sonido de los botones vanilla. Para quitarlo, borrar esas líneas.

## F. Nombre y versión del mod sobre "Seed:" (abajo a la izquierda), con la escala de la UI
- `screen/MapCanvas.java` `drawHud()`: dibuja nombre + versión y la seed con la misma escala que el resto de la interfaz.
- `KiroxMap.version()` lee `assets/kirox_map/mod_info.properties`, que Gradle rellena con la versión del proyecto
  (`build-logic/.../multiloader-common.gradle`, línea `filesMatching`). Si no se rellena, muestra "dev".

## G. Relieve del terreno + cubiomes 2026-10-02 (2026-10-14)

Sombreado de relieve (hillshade, luz desde el noroeste) sobre los colores de bioma, (el core se queda en su versión actual; ver abajo cómo subirlo).

**Core:** el relieve usa funciones que ya están en la versión actual de los bindings (`mapApproxHeight`, `mapEndSurfaceHeight`,
`Generator.en`), así que `gradle.properties` sigue en `2026.9.22…+18edd56`. Hay una build más nueva (2026-10-02, commit `4f04235`,
añade soporte ARM; la API que usa el mod no cambia). Su nombre exacto lleva la hora de la compilación en CI y no se puede
calcular: copiarlo de https://maven.xpple.dev/maven2/dev/xpple/cubiomes/ si se quiere subir. Los bindings piden JDK 23+; el proyecto usa Java 25.

**Qué hace:** cada tile pide alturas aproximadas a cubiomes y calcula un byte de sombra por texel; se aplica al color del bioma.
- Overworld: `mapApproxHeight` (altura aproximada desde el parámetro de clima "depth"), en todos los niveles de zoom
  (con menos fuerza en los tiles lejanos, ver `Relief.strength`).
- End: `mapEndSurfaceHeight`, solo al zoom más cercano (4 bloques/texel).
- Nether: cubiomes no da alturas, sin relieve (el botón se desactiva).
- Es una **aproximación**: se ven bien montañas, valles y costas, pero no coincide bloque a bloque con el mundo real.

**Desactivar sin tocar código:** botón nuevo en la barra superior, o `"showRelief": false` en `config/kirox-map.json`.

**Archivos nuevos:** `map/Relief.java` (matemática pura), `test/.../ReliefTest.java`, `textures/gui/icons/relief.png(.mcmeta)`,
`tools/generate_icons.py` (`python3 tools/generate_icons.py relief` regenera solo ese icono).

**Archivos modificados**
- `world/SeedWorld.java`: `hasRelief()` y `heights()`.
- `map/TileCache.java`: `setRelief()`, `loadOrComputeShade()`, la sombra viaja en `Tile`/`Finished`/`Job` y se aplica al pintar.
- `map/DiskCache.java`: `FORMAT = 2` (borra las cachés viejas una vez), `loadShade()` / `saveShade()` (archivos `r<scale>_<tx>_<tz>.z`).
- `config/AtlasConfig.java`: `showRelief` (por defecto `true`). `screen/AtlasSession.java`: `applyRelief()`.
- `screen/AtlasScreen.java`: botón de relieve (grupo superior con 5 botones). `screen/ToolIcon.java`: `RELIEF`. `lang/*.json`: `kiroxmap.relief`.

**Ajustar el aspecto:** `Relief.GAIN` (fuerza), `Relief.MAX_DARKEN` / `MAX_LIGHTEN` (cuánto oscurece / aclara),
`Relief.WATER_FACTOR` (relieve del fondo marino), `Relief.strength()` (fuerza por zoom).

## H. Tags de waypoint en el mundo (1.0.4, 2026-11-13)

Cada waypoint visible muestra sobre la vista del juego un rectángulo oscuro con el nombre y debajo la distancia. Lejos de la mira es pequeño y
al 40 % de opacidad; al mirarlo directamente crece (x2 respecto al tamaño base) y pasa al 90 %; al apartar la vista vuelve suave. Sin rayo ni
geometría 3D. Activo por defecto; botón en la barra superior del mapa (M) o `"showWaypointTags": false` en `config/kirox-map.json`.
El tag va a la altura de los ojos (los waypoints no tienen Y). La distancia se muestra en bloques ("B"), solo al mirar el tag (sin mirarlo solo se ve el nombre), el tag se oculta a 20 bloques o menos y el nombre se corta a 11 caracteres + "...." (`HIDE_DISTANCE`, `MAX_NAME_LENGTH`). El botón va en una placa propia, a la derecha de los toggles de capas.

**Archivos nuevos**
- `common/.../client/WaypointTags.java` (proyección, animación y dibujo)
- `common/.../textures/gui/icons/waypoint_tags.png` y `.mcmeta` (icono; sale de `tools/generate_icons.py waypoint_tags`)

**Archivos modificados**
- `config/AtlasConfig.java`: campo `showWaypointTags`.
- `screen/ToolIcon.java`: valor `WAYPOINT_TAGS`.
- `screen/AtlasScreen.java`: 6.º botón del grupo de capas (`groups = {.., 6}`), `dimOf` y nuevo `worldKeyOf(Minecraft)` públicos y estáticos, import `Minecraft`.
- `screen/KiroxConfigScreen.java`: toggle `kiroxmap.waypoint_tags`.
- `lang/en_us.json`, `lang/es_es.json`: clave `kiroxmap.waypoint_tags`.
- `fabric/.../KiroxMapFabricClient.java`: registro del elemento HUD. `neoforge/.../KiroxMapNeoForgeClient.java`: `registerGuiLayers`.
- `tools/generate_icons.py`, `gradle.properties` (1.0.4), `README.md`, `CORE_CONTEXT.md`.

**Revertir:** borrar `WaypointTags.java` y quitar el registro HUD de cada loader y el botón de `AtlasScreen`; el resto es inocuo.
**Ajustes rápidos** (constantes de `WaypointTags.java`): `BASE_SCALE`, `FOCUS_SCALE`, `BASE_ALPHA`, `FOCUS_ALPHA`, `FOCUS_MARGIN`, `EASE_SPEED`.

## I. Altura (Y) en los waypoints y waypoints dentro del save del mundo (1.0.4, 2026-11-13)

- `Waypoint.y`: al crear un waypoint se toma la Y del jugador (si el mapa muestra la dimensión donde está; si no, 65). El formulario tiene ahora un campo Y editable
  y la tarjeta del mapa muestra `x, y, z`. Los waypoints antiguos no tienen Y en el JSON: Gson les deja el valor por defecto 65 (`Waypoint.DEFAULT_Y`).
  El tag del mundo flota 1,5 bloques sobre la Y y la distancia es 3D.
- Singleplayer: los waypoints también se guardan en `<carpeta del mundo>/kirox-map/waypoints.json` (lista JSON editable a mano). Al abrir el mundo, si el archivo existe se
  lee de ahí (manda sobre el config); si no existe, se crea copiando los waypoints actuales. Si el archivo está roto no se sobrescribe y se usa el config. En multijugador sigue todo en `config/kirox-map.json`.
  `config/kirox-map.json` sigue guardando también los waypoints (no se quitó nada).

**Archivo nuevo:** `common/.../config/WorldWaypoints.java`.
**Modificados:** `config/Waypoint.java` (campo `y`, constructor con Y, `sanitize`), `config/AtlasConfig.java` (`save()` llama a `WorldWaypoints.saveCurrent`), `client/KiroxClient.java` (`sync` en cada tick con jugador),
`screen/AtlasScreen.java` (Y del jugador al crear, `sync` en `init`), `screen/WaypointScreen.java` (campo Y), `screen/MapCanvas.java` (tarjeta con Y), `client/WaypointTags.java` (usa Y y distancia 3D).
**Revertir:** borrar `WorldWaypoints.java` y las 3 llamadas (`AtlasConfig.save`, `KiroxClient.tick`, `AtlasScreen.init`); el campo `y` es inocuo.

## J. Tags fijos en el mundo al girar la cámara (1.0.4, 2026-11-13)

`WaypointTags.render(graphics, partialTick)` usa ahora `getEyePosition(partialTick)`, `getViewYRot(partialTick)` y `getViewXRot(partialTick)` (los mismos valores interpolados que usa la
cámara del juego) en vez de `getYRot()`/`getXRot()`, que van hasta un tick por detrás al mover el ratón y hacían que el tag "bailara". Los dos loaders pasan
`deltaTracker.getGameTimeDeltaPartialTick(true)`.
