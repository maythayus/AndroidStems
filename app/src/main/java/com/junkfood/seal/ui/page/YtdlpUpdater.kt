package com.junkfood.seal.ui.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.Downloader
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getLong
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.UpdateUtil
import com.junkfood.seal.util.YT_DLP_AUTO_UPDATE
import com.junkfood.seal.util.YT_DLP_UPDATE_INTERVAL
import com.junkfood.seal.util.YT_DLP_UPDATE_TIME
import com.junkfood.seal.util.YT_DLP_VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun YtdlpUpdater() {

    val downloaderState by Downloader.downloaderState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (downloaderState !is Downloader.State.Idle) return@LaunchedEffect

        // Premier lancement : le yt-dlp inclus dans l'APK est souvent déjà trop vieux pour YouTube.
        // On le met donc à jour tout de suite, même en données mobiles (quelques Mo), sans
        // attendre l'intervalle habituel, et on réessaie si le réseau n'est pas encore prêt.
        val neverUpdated = YT_DLP_VERSION.getString().isEmpty()

        if (!neverUpdated) {
            if (!YT_DLP_AUTO_UPDATE.getBoolean()) return@LaunchedEffect
            if (!PreferenceUtil.isNetworkAvailableForDownload()) return@LaunchedEffect
            val lastUpdateTime = YT_DLP_UPDATE_TIME.getLong()
            if (System.currentTimeMillis() < lastUpdateTime + YT_DLP_UPDATE_INTERVAL.getLong()) {
                return@LaunchedEffect
            }
        }

        val retryDelaysMs = if (neverUpdated) listOf(0L, 5_000L, 15_000L, 30_000L) else listOf(0L)
        for (wait in retryDelaysMs) {
            if (wait > 0) delay(wait)
            if (Downloader.downloaderState.value !is Downloader.State.Idle) break
            val success =
                runCatching {
                        Downloader.updateState(state = Downloader.State.Updating)
                        withContext(Dispatchers.IO) { UpdateUtil.updateYtDlp() }
                    }
                    .onFailure { it.printStackTrace() }
                    .isSuccess
            Downloader.updateState(state = Downloader.State.Idle)
            if (success && YT_DLP_VERSION.getString().isNotEmpty()) break
        }
    }
}
