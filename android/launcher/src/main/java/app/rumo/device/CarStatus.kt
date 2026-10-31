package app.rumo.device

import android.os.IBinder
import android.os.Parcel

/**
 * Estado do carro pelo serviço "carstatus" do fabricante (HCN, interface android.carstatus.ICarStatus), o mesmo
 * que acende o modo noturno do Android Auto com o farol. Binder puro, com os números de transação lidos no aparelho:
 * getParkingStatus = 1, getReversing = 2, getAccStatus = 3, getHeadLightStatus = 7.
 */
object CarStatus {
    private const val SERVICE = "carstatus"
    private const val DESCRIPTOR = "android.carstatus.ICarStatus"
    private const val TX_HEADLIGHT = 7

    private var binder: IBinder? = null

    /** Farol aceso; null se o aparelho não tiver o serviço. Chamada ao sistema: fora da thread principal. */
    fun headlight(): Boolean? {
        val b = service() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            b.transact(TX_HEADLIGHT, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (e: Exception) {
            binder = null
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun service(): IBinder? {
        binder?.takeIf { it.isBinderAlive }?.let { return it }
        binder = try {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, SERVICE) as IBinder?
        } catch (e: Throwable) {
            null
        }
        return binder
    }
}
