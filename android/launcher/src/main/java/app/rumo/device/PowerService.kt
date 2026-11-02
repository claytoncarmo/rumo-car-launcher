package app.rumo.device

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.rumo.core.isRestartLabel

/**
 * Serviço de acessibilidade só para reiniciar o Android sem root: abre o menu de energia do sistema
 * ([openPowerMenu]) e toca em "Reiniciar". Fica parado: só olha a tela por alguns segundos depois do pedido, não lê
 * nem guarda nada. O usuário liga em Configurações › Acessibilidade.
 */
class PowerService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        connected = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
            connected = false
        }
        armed = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!armed) return
        if (SystemClock.elapsedRealtime() > deadline) {
            armed = false
            return
        }
        // Só janelas de apps do sistema (o menu de energia é do Android ou da SystemUI): um "Reiniciar" num app comum
        // aberto na hora não é clicado
        val roots = buildList {
            rootInActiveWindow?.let(::add)
            windows.forEach { w -> w.root?.let(::add) }
        }
        try {
            for (root in roots) {
                if (!isSystemApp(root.packageName)) continue
                val node = findRestart(root, 0) ?: continue
                val clicked = click(node)
                if (node !== root) node.recycle()
                if (clicked) {
                    // O Android pode pedir uma confirmação: o segundo "Reiniciar" também é clicado
                    if (++clicks >= MAX_CLICKS) armed = false
                    return
                }
            }
        } finally {
            roots.forEach { it.recycle() }
        }
    }

    private fun isSystemApp(pkg: CharSequence?): Boolean = try {
        pkg != null && packageManager.getApplicationInfo(pkg.toString(), 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** O nó com "Reiniciar" (o chamador o devolve ao Android); os outros visitados são devolvidos aqui (Android 9). */
    private fun findRestart(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (isRestartLabel(n.text) || isRestartLabel(n.contentDescription)) return n
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            val found = findRestart(child, depth + 1)
            if (found !== child) child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        // Sobe até o primeiro clicável (o texto costuma estar dentro do botão)
        var node = n.parent
        while (node != null) {
            val done = node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val up = if (done) null else node.parent
            node.recycle()
            if (done) return true
            node = up
        }
        return false
    }

    companion object {
        @Volatile private var instance: PowerService? = null
        @Volatile private var armed = false
        @Volatile private var deadline = 0L
        @Volatile private var clicks = 0
        private const val ARM_MS = 10_000L
        private const val MAX_CLICKS = 2
        private const val MAX_DEPTH = 20

        /** O serviço está ligado em Acessibilidade (a tela mostra isso). */
        var connected by mutableStateOf(false)
            private set

        /** Abriu o menu de energia e ainda não achou o "Reiniciar" para tocar. */
        val waiting: Boolean get() = armed && clicks == 0

        /** Abre o menu de energia do sistema e passa a procurar o "Reiniciar". Chamar na thread principal. */
        fun openPowerMenu(): Boolean {
            val service = instance ?: return false
            clicks = 0
            deadline = SystemClock.elapsedRealtime() + ARM_MS
            armed = true
            val opened = service.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            if (!opened) armed = false
            return opened
        }

        fun disarm() {
            armed = false
        }
    }
}
