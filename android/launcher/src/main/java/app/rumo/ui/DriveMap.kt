package app.rumo.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.rumo.core.Radar
import app.rumo.core.radarsGeoJson
import app.rumo.device.GpsInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File

/** Altura do carro no card (fração a partir do topo): o mapa mostra mais o que vem pela frente. */
const val CAR_Y = 0.7f

/** Última posição conhecida, guardada para o mapa já abrir no lugar certo depois de ligar o carro. */
class MapStart(val lat: Double, val lon: Double, val bearing: Double)

/**
 * Mapa passivo do card: segue o GPS com a frente do carro para cima, sem gestos (tocar no card abre o Android Auto).
 * Mapa vetorial offline (um map.pmtiles por estado instalado, todos desenhados) e fontes dos nomes de rua da pasta
 * Rumo; até 30 quadros por segundo.
 *
 * [preview]: o mesmo mapa em pequeno, para ver um ponto marcado enquanto ele é editado (aba Pontos): centrado na
 * "posição" dada, bem mais perto, com o rumo dela para cima (ou o norte, sem rumo), 15 quadros por segundo e em
 * TextureView, para ficar por cima do mapa da tela inicial (que continua por baixo da folha).
 */
@Composable
fun DriveMap(
    mapFiles: List<File>,
    fontsDir: File,
    radars: List<Radar>,
    gps: GpsInfo,
    start: MapStart?,
    night: Boolean,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var height by remember { mutableIntStateOf(0) }
    // A primeira posição pula direto (sem deslizar desde o meio do oceano)
    val placed = remember { booleanArrayOf(false) }

    val view = remember {
        MapLibre.getInstance(context)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(preview)
            .logoEnabled(false)
            .attributionEnabled(false)
            .compassEnabled(false)
            .maxZoomPreference(if (preview) PREVIEW_ZOOM else 18.0)
        MapView(context, options).apply {
            onCreate(null)
            setMaximumFps(if (preview) 15 else 30)
            getMapAsync { m ->
                m.uiSettings.setAllGesturesEnabled(false)
                map = m
            }
        }
    }

    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            val state = lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            view.onDestroy()
        }
    }

    // Os radares mais recentes, para o estilo novo já nascer com eles
    val latestRadars = rememberUpdatedState(radars)
    LaunchedEffect(map, night, mapFiles) {
        val m = map ?: return@LaunchedEffect
        // Dezenas de milhares de pontos: o texto é montado fora da thread principal
        val points = withContext(Dispatchers.Default) { radarsGeoJson(latestRadars.value) }
        m.setStyle(Style.Builder().fromJson(mapStyle(mapFiles, fontsDir, night))) { style ->
            style.getSourceAs<GeoJsonSource>(RADAR_SOURCE)?.setGeoJson(points)
        }
    }
    // Radares novos (a junção com o MapaRadar termina depois do mapa) trocam só os dados, sem recarregar o estilo
    LaunchedEffect(map, radars) {
        val m = map ?: return@LaunchedEffect
        val points = withContext(Dispatchers.Default) { radarsGeoJson(radars) }
        m.getStyle { style -> style.getSourceAs<GeoJsonSource>(RADAR_SOURCE)?.setGeoJson(points) }
    }

    LaunchedEffect(map, gps.lat, gps.lon, gps.bearing, height, start) {
        val m = map ?: return@LaunchedEffect
        // Sem GPS ainda: a última posição guardada
        val lat = gps.lat ?: start?.lat ?: return@LaunchedEffect
        val lon = gps.lon ?: start?.lon ?: return@LaunchedEffect
        // Centro da área abaixo do recuo = altura do carro no card (na pré-visualização, o meio)
        val top = if (preview) 0.0 else (height * (2 * CAR_Y - 1)).toDouble()
        val position = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(if (preview) PREVIEW_ZOOM else zoomFor(gps.speedKmh ?: 0))
            .bearing(
                if (preview) gps.bearing?.toDouble() ?: 0.0 else gps.bearing?.toDouble() ?: start?.bearing ?: m.cameraPosition.bearing,
            )
            .tilt(0.0)
            .padding(0.0, top, 0.0, 0.0)
            .build()
        val update = CameraUpdateFactory.newCameraPosition(position)
        if (placed[0]) {
            // Uma posição por segundo: desliza até a próxima no mesmo tempo, sem tranco
            m.easeCamera(update, if (preview) 250 else 1000, false)
        } else {
            m.moveCamera(update)
            placed[0] = true
        }
    }

    AndroidView(factory = { view }, modifier = modifier.fillMaxSize().onSizeChanged { height = it.height })
}

/** Zoom da pré-visualização de um ponto: perto o bastante para ver 5 m de diferença. */
private const val PREVIEW_ZOOM = 19.0

/** Mais perto na cidade, mais longe na estrada. */
private fun zoomFor(kmh: Int): Double = when {
    kmh < 30 -> 16.5
    kmh < 60 -> 16.0
    kmh < 90 -> 15.3
    else -> 14.7
}

/**
 * Cores do mapa: tons de cinza quase neutros (a água só um pouco diferente do fundo). O map.pmtiles do app só traz o que
 * é desenhado aqui (água, rios e vias para carro; `data/map_filter.py`): se incluir outra camada, mude os dois.
 */
private class MapColors(
    val land: String, val water: String, val minor: String,
    val major: String, val highway: String, val casing: String?, val label: String, val halo: String,
)

private val NIGHT_MAP = MapColors(
    land = "#121417", water = "#1B2026", minor = "#2A2E33",
    major = "#373C42", highway = "#454A51", casing = null, label = "#A3A8AE", halo = "#121417",
)
private val DAY_MAP = MapColors(
    land = "#ECEDEE", water = "#D2D8DD", minor = "#FFFFFF",
    major = "#FFFFFF", highway = "#FAFAF7", casing = "#CDD0D3", label = "#4F5359", halo = "#FFFFFF",
)

/**
 * Estilo do mapa: uma fonte por estado instalado. As camadas são agrupadas por tipo (primeiro a água de todos os
 * estados, depois as vias de todos…) para que, na faixa de folga da divisa, a água de um estado não cubra as vias do
 * vizinho.
 */
internal fun mapStyle(mapFiles: List<File>, fontsDir: File, night: Boolean): String {
    val c = if (night) NIGHT_MAP else DAY_MAP
    val glyphs = JSONObject.quote("file://" + fontsDir.absolutePath + "/{fontstack}/{range}.pbf")
    val minorW = """{"base":1.5,"stops":[[12,0.6],[15,3],[18,16]]}"""
    val majorW = """{"base":1.5,"stops":[[9,0.8],[15,6],[18,24]]}"""
    val highwayW = """{"base":1.5,"stops":[[7,1],[15,8],[18,28]]}"""
    val sources = mapFiles.indices.joinToString(",") { i ->
        "\"osm$i\": {\"type\": \"vector\", \"url\": ${JSONObject.quote("pmtiles://file://" + mapFiles[i].absolutePath)}}"
    }
    // Cada tipo de camada, uma vez por fonte
    fun perSource(layer: (src: String, id: String) -> String) =
        mapFiles.indices.map { layer("osm$it", "$it") }

    val water = perSource { src, i ->
        """{"id":"water$i","type":"fill","source":"$src","source-layer":"water","filter":["==","${'$'}type","Polygon"],
         "paint":{"fill-color":"${c.water}"}}"""
    }
    val rivers = perSource { src, i ->
        """{"id":"rivers$i","type":"line","source":"$src","source-layer":"water",
         "filter":["all",["==","${'$'}type","LineString"],["in","kind","river","canal"]],
         "layout":{"line-cap":"round","line-join":"round"},
         "paint":{"line-color":"${c.water}","line-width":{"base":1.4,"stops":[[10,1],[15,3],[18,8]]}}}"""
    }
    val casingMinor = c.casing?.let { col ->
        perSource { src, i ->
            """{"id":"casing-minor$i","type":"line","source":"$src","source-layer":"roads","filter":["==","kind","minor_road"],
         "minzoom":13,"layout":{"line-cap":"round","line-join":"round"},
         "paint":{"line-color":"$col","line-width":{"base":1.5,"stops":[[12,1.6],[15,5],[18,19]]}}}"""
        }
    }.orEmpty()
    val casingMajor = c.casing?.let { col ->
        perSource { src, i ->
            """{"id":"casing-major$i","type":"line","source":"$src","source-layer":"roads","filter":["in","kind","major_road","highway"],
         "layout":{"line-cap":"round","line-join":"round"},
         "paint":{"line-color":"$col","line-width":{"base":1.5,"stops":[[9,1.8],[15,8.5],[18,30]]}}}"""
        }
    }.orEmpty()
    val minor = perSource { src, i ->
        """{"id":"minor$i","type":"line","source":"$src","source-layer":"roads","filter":["==","kind","minor_road"],"minzoom":12,
         "layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":"${c.minor}","line-width":$minorW}}"""
    }
    val major = perSource { src, i ->
        """{"id":"major$i","type":"line","source":"$src","source-layer":"roads","filter":["==","kind","major_road"],
         "layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":"${c.major}","line-width":$majorW}}"""
    }
    val highway = perSource { src, i ->
        """{"id":"highway$i","type":"line","source":"$src","source-layer":"roads","filter":["==","kind","highway"],
         "layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":"${c.highway}","line-width":$highwayW}}"""
    }
    val names = perSource { src, i ->
        """{"id":"names$i","type":"symbol","source":"$src","source-layer":"roads","minzoom":14,
         "filter":["in","kind","minor_road","major_road","highway"],
         "layout":{"symbol-placement":"line","text-field":"{name}","text-font":["NotoSansMedium"],"text-size":15,
                   "text-max-angle":30,"symbol-spacing":320},
         "paint":{"text-color":"${c.label}","text-halo-color":"${c.halo}","text-halo-width":1.6}}"""
    }
    val land = """{"id":"land","type":"background","paint":{"background-color":"${c.land}"}}"""
    val layers = (listOf(land) + water + rivers + casingMinor + casingMajor + minor + major + highway + names + radarLayers())
        .joinToString(",\n")
    return """
    {
      "version": 8,
      "glyphs": $glyphs,
      "sources": {$sources${if (sources.isEmpty()) "" else ","}"$RADAR_SOURCE": {"type": "geojson", "data": {"type": "FeatureCollection", "features": []}}},
      "layers": [
        $layers
      ]
    }
    """.trimIndent()
}

/** Fonte GeoJSON dos radares: começa vazia e recebe os pontos depois que o estilo carrega. */
internal const val RADAR_SOURCE = "radars"

/**
 * Radares e demais pontos de atenção (pedágio, polícia, lombada…) por cima das ruas e dos nomes: um círculo por tipo e,
 * dentro dele, o limite (radares) ou uma letra (L lombada, S câmera de semáforo, P polícia, $ pedágio).
 */
private fun radarLayers(): List<String> {
    val kind = """["get","kind"]"""
    // amarelo: radar fixo e lombada eletrônica; laranja: semáforo com radar e radar móvel; cinza: lombada; vermelho:
    // câmera de semáforo; azul: polícia; verde: pedágio
    val color = """["match",$kind,0,"#FACC15",1,"#FACC15",5,"#FACC15",2,"#F97316",3,"#EA580C",4,"#9CA3AF",6,"#EF4444",7,"#3B82F6",8,"#10B981","#FACC15"]"""
    val ink = """["match",$kind,6,"#FFFFFF",7,"#FFFFFF",8,"#FFFFFF","#1A1400"]"""
    // As lombadas comuns são muitas: círculos menores
    val radius = """["interpolate",["linear"],["zoom"],12,["match",$kind,4,2.5,5],15,["match",$kind,4,5,10],17,["match",$kind,4,8,15]]"""
    val label = """["case",[">",["get","lim"],0],["to-string",["get","lim"]],["match",$kind,6,"S",7,"P",8,"${'$'}",""]]"""
    return listOf(
        """{"id":"radar-dot","type":"circle","source":"$RADAR_SOURCE","minzoom":12,
         "paint":{"circle-color":$color,"circle-radius":$radius,"circle-stroke-color":"#FFFFFF","circle-stroke-width":2,
                  "circle-pitch-alignment":"map"}}""",
        """{"id":"radar-text","type":"symbol","source":"$RADAR_SOURCE","minzoom":14,
         "layout":{"text-field":$label,"text-font":["NotoSansMedium"],"text-size":["interpolate",["linear"],["zoom"],14,9,17,15],
                   "text-allow-overlap":true,"text-ignore-placement":true},
         "paint":{"text-color":$ink}}""",
    )
}
