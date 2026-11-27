package ai.devcodex.agent.shell

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/**
 * v0.4.16 统一原生环境模块（PRootUbuntu / TermuxRuntime 共用）。
 * 核心迁移：PRoot 二进制走 jniLibs → nativeLibraryDir（Android 全 ROM 豁免的 W^X 执行区，
 * 修 vivo Android 11 对 filesDir ELF 的 execve EACCES）。
 */
object PRootEnv {

    private fun nativeDir(ctx: Context): File =
        File(ctx.applicationInfo.nativeLibraryDir).also {
            check(it.isDirectory) { "Android nativeLibraryDir 不可用: ${it.absolutePath}" }
        }

    fun nativeBinary(ctx: Context, name: String): File {
        val f = File(nativeDir(ctx), name)
        check(f.isFile && f.canExecute()) {
            "缺少可执行原生库 $name（${f.absolutePath}），请检查 jniLibs 与 extractNativeLibs=true"
        }
        return f
    }

    fun prootBinary(ctx: Context): File = nativeBinary(ctx, "libproot.so")
    fun loaderBinary(ctx: Context): File = nativeBinary(ctx, "libproot_loader.so")

    fun tmpDir(ctx: Context): File =
        File(ctx.filesDir, "proot-tmp").apply { mkdirs() }

    /**
     * APK 只解压 lib*.so，但 libproot.so 的 DT_NEEDED 是 libtalloc.so.2。
     * 通过私有目录软链指向 nativeLibraryDir/libtalloc.so——APK 升级后 nativeLibraryDir
     * 变化时软链自动刷新（覆盖升级场景）。
     */
    @Synchronized
    fun libraryPath(ctx: Context): String {
        val native = nativeDir(ctx)
        val talloc = nativeBinary(ctx, "libtalloc.so").toPath()
        nativeBinary(ctx, "libandroid-shmem.so") // 启动前校验依赖完整

        val aliasDir = File(ctx.filesDir, "proot-libs")
        Files.createDirectories(aliasDir.toPath())
        val alias = File(aliasDir, "libtalloc.so.2").toPath()
        if (Files.exists(alias, NOFOLLOW_LINKS)) {
            if (!Files.isSymbolicLink(alias) || Files.readSymbolicLink(alias) != talloc) {
                Files.delete(alias)
            }
        }
        if (!Files.exists(alias, NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(alias, talloc)
        }
        return "${aliasDir.absolutePath}:${native.absolutePath}"
    }

    /** 为启动 libproot.so 的 ProcessBuilder 注入干净的宿主环境（不污染客体） */
    fun applyHostEnvironment(ctx: Context, pb: ProcessBuilder, noSeccomp: Boolean = false) {
        val env = pb.environment()
        env.remove("LD_PRELOAD")
        env["LD_LIBRARY_PATH"] = libraryPath(ctx)
        env["PROOT_LOADER"] = loaderBinary(ctx).absolutePath
        env.remove("PROOT_LOADER_32")   // arm64-only APK 无 32 位 loader
        env.remove("PROOT_LOADER32")
        env["PROOT_TMP_DIR"] = tmpDir(ctx).absolutePath
        if (noSeccomp) {
            env["PROOT_NO_SECCOMP"] = "1"
        } else {
            env.remove("PROOT_NO_SECCOMP")
        }
    }

    /** status() 探测用：proot 可执行即认为原生层就绪 */
    fun prootReady(ctx: Context): Boolean =
        runCatching { prootBinary(ctx).canExecute() }.getOrDefault(false)
}
