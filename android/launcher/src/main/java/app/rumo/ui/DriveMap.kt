package app.rumo.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.rumo.device.GpsInfo
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File

/** Altura do carro no card (fração a partir do topo): o mapa mostra mais o que vem pela frente. */
const val CAR_Y = 0.7f

/** Última posição conhecida, guardada para o mapa já abrir no lugar certo depois de ligar o carro. */
class MapStart(val lat: Double, val lon: Double, val bearing: Double)

/**
 * Mapa passivo do card: segue o GPS com a frente do carro para cima, sem gestos (tocar no card abre o Android Auto).
 * Mapa vetorial offline (mapa.pmtiles) e fontes dos nomes de rua da pasta Rumo; até 30 quadros por segundo.
 */
@Composable
fun DriveMap(mapFile: File, gps: GpsInfo, start: MapStart?, night: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var height by remember { mutableIntStateOf(0) }
    // A primeira posição pula direto (sem deslizar desde o meio do oceano)
    val placed = remember { booleanArrayOf(false) }

    val view = remember {
        MapLibre.getInstance(context)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(false)
            .logoEnabled(false)
            .attributionEnabled(false)
            .compassEnabled(false)
            .maxZoomPreference(18.0)
        MapView(context, options).apply {
            onCreate(null)
            setMaximumFps(30)
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

    LaunchedEffect(map, night, mapFile) {
        map?.setStyle(Style.Builder().fromJson(mapStyle(mapFile, night)))
    }

    LaunchedEffect(map, gps.lat, gps.lon, gps.bearing, height, start) {
        val m = map ?: return@LaunchedEffect
        // Sem GPS ainda: a última posição guardada
        val lat = gps.lat ?: start?.lat ?: return@LaunchedEffect
        val lon = gps.lon ?: start?.lon ?: return@LaunchedEffect
        // Centro da área abaixo do recuo = altura do carro no card
        val top = (height * (2 * CAR_Y - 1)).toDouble()
        val position = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(zoomFor(gps.speedKmh ?: 0))
            .bearing(gps.bearing?.toDouble() ?: start?.bearing ?: m.cameraPosition.bearing)
            .tilt(0.0)
            .padding(0.0, top, 0.0, 0.0)
            .build()
        val update = CameraUpdateFactory.newCameraPosition(position)
        if (placed[0]) {
            // Uma posição por segundo: desliza até a próxima no mesmo tempo, sem tranco
            m.easeCamera(update, 1000, false)
        } else {
            m.moveCamera(update)
            placed[0] = true
        }
    }

    AndroidView(factory = { view }, modifier = modifier.fillMaxSize().onSizeChanged { height = it.height })
}

/** Mais perto na cidade, mais longe na estrada. */
private fun zoomFor(kmh: Int): Double = when {
    kmh < 30 -> 16.5
    kmh < 60 -> 16.0
    kmh < 90 -> 15.3
    else -> 14.7
}

/** Cores do mapa: tons de cinza quase neutros (água e áreas verdes só um pouco diferentes do fundo). */
private class MapColors(
    val land: String, val water: String, val park: String, val minor: String,
    val major: String, val highway: String, val casing: String?, val label: String, val halo: String,
)

private val NIGHT_MAP = MapColors(
    land = "#121417", water = "#1B2026", park = "#15191A", minor = "#2A2E33",
    major = "#373C42", highway = "#454A51", casing = null, label = "#A3A8AE", halo = "#121417",
)
private val DAY_MAP = MapColors(
    land = "#ECEDEE", water = "#D2D8DD", park = "#E2E5E0", minor = "#FFFFFF",
    major = "#FFFFFF", highway = "#FAFAF7", casing = "#CDD0D3", label = "#4F5359", halo = "#FFFFFF",
)

internal fun mapStyle(mapFile: File, night: Boolean): String {
    val c = if (night) NIGHT_MAP else DAY_MAP
    val tiles = JSONObject.quote("pmtiles://file://" + mapFile.absolutePath)
    val glyphs = JSONObject.quote("file://" + File(mapFile.parentFile, "fontes").absolutePath + "/{fontstack}/{range}.pbf")
    val minorW = """{"base":1.5,"stops":[[12,0.6],[15,3],[18,16]]}"""
    val majorW = """{"base":1.5,"stops":[[9,0.8],[15,6],[18,24]]}"""
    val highwayW = """{"base":1.5,"stops":[[7,1],[15,8],[18,28]]}"""
    val casing = c.casing?.let { col ->
        """
        {"id":"casing-minor","type":"line","source":"osm","source-layer":"roads","filter":["==","kind","minor_road"],
         "minzoom":13,"layout":{"line-cap":"round","line-join":"round"},
         "paint":{"line-color":"$col","line-width":{"base":1.5,"stops":[[12,1.6],[15,5],[18,19]]}}},
        {"id":"casing-major","type":"line","source":"osm","source-layer":"roads","filter":["in","kind","major_road","highway"],
         "layout":{"line-cap":"round","line-join":"round"},
         "paint":{"line-color":"$col","line-width":{"base":1.5,"stops":[[9,1.8],[15,8.5],[18,30]]}}},
        """.trimIndent()
    } ?: ""
    return """
    {
      "version": 8,
      "glyphs": $glyphs,
      "sources": {"osm": {"type": "vector", "url": $tiles}},
      "layers": [
        {"id":"land","type":"background","paint":{"background-color":"${c.land}"}},
        {"id":"park","type":"fill","source":"osm","source-layer":"landuse",
         "filter":["all",["==","${'$'}type","Polygon"],
                   ["in","kind","park","forest","wood","nature_reserve","national_park","garden","grass","meadow","golf_course"]],
         "paint":{"fill-color":"${c.park}"}},
        {"id":"water","type":"fill","source":"osm","source-layer":"water","filter":["==","${'$'}type","Polygon"],
         "paint":{"fill-color":"${c.water}"}},
        {"id":"rivers","type":"line","source":"osm","source-layer":"water",
         "filter":["all",["==","${'$'}type","LineString"],["in","kind","river","canal"]],
         "layout":{"line-cap":"round","line-join":"round"},
         "paint":{"line-color":"${c.water}","line-width":{"base":1.4,"stops":[[10,1],[15,3],[18,8]]}}},
        {"id":"streams","type":"line","source":"osm","source-layer":"water","minzoom":13,
         "filter":["all",["==","${'$'}type","LineString"],["!in","kind","river","canal"]],
         "layout":{"line-cap":"round","line-join":"round"},
         "paint":{"line-color":"${c.water}","line-width":{"base":1.4,"stops":[[13,0.6],[15,1.5],[18,4]]}}},
        $casing
        {"id":"minor","type":"line","source":"osm","source-layer":"roads","filter":["==","kind","minor_road"],"minzoom":12,
         "layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":"${c.minor}","line-width":$minorW}},
        {"id":"major","type":"line","source":"osm","source-layer":"roads","filter":["==","kind","major_road"],
         "layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":"${c.major}","line-width":$majorW}},
        {"id":"highway","type":"line","source":"osm","source-layer":"roads","filter":["==","kind","highway"],
         "layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":"${c.highway}","line-width":$highwayW}},
        {"id":"names","type":"symbol","source":"osm","source-layer":"roads","minzoom":14,
         "filter":["in","kind","minor_road","major_road","highway"],
         "layout":{"symbol-placement":"line","text-field":"{name}","text-font":["NotoSansMedium"],"text-size":15,
                   "text-max-angle":30,"symbol-spacing":320},
         "paint":{"text-color":"${c.label}","text-halo-color":"${c.halo}","text-halo-width":1.6}}
      ]
    }
    """.trimIndent()
}
