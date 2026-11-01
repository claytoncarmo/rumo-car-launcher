package app.rumo.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RadarGeoJsonTest {
    @Test
    fun oneFeaturePerPointWithKindAndLimit() {
        val json = JSONObject(
            radarsGeoJson(
                listOf(
                    Radar(1, -27.5, -48.5, 60, 90, RadarKind.FIXED),
                    Radar(2, -27.6, -48.6, 0, -1, RadarKind.TOLL),
                ),
            ),
        )
        val f = json.getJSONArray("features")
        assertEquals(2, f.length())
        val a = f.getJSONObject(0)
        assertEquals(RadarKind.FIXED, a.getJSONObject("properties").getInt("kind"))
        assertEquals(60, a.getJSONObject("properties").getInt("lim"))
        // GeoJSON é longitude, latitude
        assertEquals(-48.5, a.getJSONObject("geometry").getJSONArray("coordinates").getDouble(0), 1e-9)
        assertEquals(-27.5, a.getJSONObject("geometry").getJSONArray("coordinates").getDouble(1), 1e-9)
    }

    @Test
    fun noPointsIsEmptyCollection() {
        assertEquals(0, JSONObject(radarsGeoJson(emptyList())).getJSONArray("features").length())
    }
}
