package app.rumo.core

private val RESTART_LABELS = setOf("reiniciar", "reiniciar agora", "reinicializar", "restart", "reboot")

/** O texto de um botão do menu de energia que reinicia o aparelho (comparação exata, para não clicar em outra coisa). */
fun isRestartLabel(text: CharSequence?): Boolean {
    val s = text?.toString()?.trim()?.lowercase(PT_BR) ?: return false
    return s in RESTART_LABELS
}

private val REBOOT_HINT = Regex("(?i)(re-?boot|restart|shut-?down|power-?off|power_off|standby)")

/** Nome de componente, ação ou método do aparelho que parece mexer em reiniciar/desligar (teste do reinício). */
fun looksLikeRebootHook(name: String): Boolean = REBOOT_HINT.containsMatchIn(name)
