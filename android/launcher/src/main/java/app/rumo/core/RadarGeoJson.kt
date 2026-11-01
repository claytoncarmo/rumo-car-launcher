package app.rumo.core

/**
 * Os radares e demais pontos do roads.db como GeoJSON (propriedades `kind` e `lim`), para o MapLibre desenhar no mapa
 * (camadas `radar-dot` e `radar-text` de ui/DriveMap.kt).
 */
fun radarsGeoJson(radars: List<Radar>): String {
    val sb = StringBuilder(radars.size * 96 + 64)
    sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
    radars.forEachIndexed { i, r ->
        if (i > 0) sb.append(',')
        sb.append("{\"type\":\"Feature\",\"properties\":{\"kind\":").append(r.kind)
            .append(",\"lim\":").append(r.limit)
            .append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[").append(r.lon).append(',').append(r.lat)
            .append("]}}")
    }
    return sb.append("]}").toString()
}
