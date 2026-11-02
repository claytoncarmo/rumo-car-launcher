package app.rumo.core

/** Endereço MAC (Wi-Fi, Bluetooth do celular): 6 pares hexadecimais separados por ":" ou "-". */
private val MAC = Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b""")

/** Ajustes e propriedades que identificam o aparelho ou o celular: o nome fica, o valor some. */
private val SECRET = Regex(
    """((?:[\w.]*(?:serial|android_id|bluetooth_address|bluetooth_name|imei|meid|wifi_mac|phoneBTMac)[\w.]*)["\]]?\s*[=:]\s*\[?)[^\s,}\]]+""",
    RegexOption.IGNORE_CASE,
)

/**
 * Tira dos relatórios de diagnóstico (que vão para a memória compartilhada e para pendrives) o que identifica o
 * aparelho ou o celular do motorista: endereços MAC, números de série, Android ID e IMEI.
 */
fun redact(line: String): String =
    MAC.replace(SECRET.replace(line) { it.groupValues[1] + "(oculto)" }, "(MAC oculto)")
