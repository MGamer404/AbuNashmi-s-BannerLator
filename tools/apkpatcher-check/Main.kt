// JVM harness for core/ApkPatcher.kt, used only by apk-delta-check.yml:
// ApkPatcherCheck OLD PATCH OUT -> exit 0 when the rebuilt APK has the sha the patch names.
import com.winlator.star.core.ApkPatcher
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val start = System.currentTimeMillis()
    val ok = ApkPatcher.apply(File(args[0]), File(args[1]), File(args[2]), AtomicBoolean()) { _, _ -> }
    println("ApkPatcher.apply ok=$ok in ${System.currentTimeMillis() - start} ms")
    exitProcess(if (ok) 0 else 1)
}
