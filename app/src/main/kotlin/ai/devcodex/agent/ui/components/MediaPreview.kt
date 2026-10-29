package ai.devcodex.agent.ui.components

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ai.devcodex.agent.tools.BuiltinTools
import coil.compose.AsyncImage
import java.io.File

/** 工具结果中由 image_generate / video_status 写入的 FILE: 行所指的媒体文件 */
private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp")
private val VIDEO_EXT = setOf("mp4", "webm")

/** 解析 FILE: 行，仅保留工作区 generated 目录内真实存在的媒体文件 */
fun parseMediaFiles(result: String?): List<File> {
    if (result.isNullOrBlank()) return emptyList()
    val dir = runCatching { File(BuiltinTools.workspaceRoot(), "generated").canonicalFile }.getOrNull() ?: return emptyList()
    val prefix = dir.path + File.separator
    return result.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("FILE:") }
        .map { it.removePrefix("FILE:").trim() }
        .mapNotNull { rel ->
            runCatching { File(BuiltinTools.workspaceRoot(), rel).canonicalFile }.getOrNull()
        }
        .filter { it.path.startsWith(prefix) && it.isFile && it.extension.lowercase() in IMAGE_EXT + VIDEO_EXT }
        .take(8)
        .toList()
}

@Composable
fun MediaPreview(result: String?, modifier: Modifier = Modifier) {
    val files = remember(result) { parseMediaFiles(result) }
    if (files.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { f ->
            if (f.extension.lowercase() in VIDEO_EXT) VideoPreview(f) else ImagePreview(f)
        }
    }
}

@Composable
private fun ImagePreview(file: File) {
    AsyncImage(
        model = file,
        contentDescription = file.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(12.dp)),
    )
}

@Composable
private fun VideoPreview(file: File) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                setVideoPath(file.absolutePath)
                // 先显示首帧，由用户点击控制条播放
                seekTo(1)
            }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp).clip(RoundedCornerShape(12.dp)),
    )
}
