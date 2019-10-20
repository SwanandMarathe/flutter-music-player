package tech.soit.quiet

import android.content.ComponentName
import android.content.Context
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.Registrar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import tech.soit.quiet.player.PlayList
import tech.soit.quiet.player.PlayListExt
import tech.soit.quiet.player.PlayMode
import tech.soit.quiet.player.setPlayMode
import tech.soit.quiet.service.MusicPlayerService
import tech.soit.quiet.utils.log
import tech.soit.quiet.utils.toMap
import tech.soit.quiet.utils.toMediaMetadataCompat

class MusicPlayerPlugin(
        private val channel: MethodChannel,
        private val context: Context
) : MethodCallHandler {

    companion object {

        private const val NAME = "tech.soit.quiet/player"

        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), NAME)
            val playerPlugin = MusicPlayerPlugin(channel, registrar.context())
            channel.setMethodCallHandler(playerPlugin)
            playerPlugin.connect()
            registrar.addViewDestroyListener {
                playerPlugin.disconnect()
                false
            }
        }
    }

    private val connectionIndicator = CompletableDeferred<Unit>()

    private val connectCallback = object : MediaBrowserCompat.ConnectionCallback() {

        override fun onConnected() {
            log { "connected , ${mediaBrowser.sessionToken}" }
            mediaController?.unregisterCallback(controllerCallback)
            mediaController = MediaControllerCompat(context, mediaBrowser.sessionToken).apply {
                registerCallback(controllerCallback)
                controllerCallback.getInitData()
            }
            connectionIndicator.complete(Unit)
        }

        override fun onConnectionSuspended() {
            log { "connect suspended" }
            mediaController?.unregisterCallback(controllerCallback)
            mediaController = null
        }

        override fun onConnectionFailed() {
            connectionIndicator.complete(Unit)
            log { "connect failed" }
            mediaController = null
            //TODO handle connect failed

        }
    }

    private val mediaBrowser: MediaBrowserCompat =
            MediaBrowserCompat(
                    context, ComponentName(context, MusicPlayerService::class.java),
                    connectCallback, null
            )


    private var mediaController: MediaControllerCompat? = null

    private val controls: MediaControllerCompat.TransportControls? get() = mediaController?.transportControls

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        GlobalScope.launch(Dispatchers.Main) { onMethodCallAsync(call, result) }
    }

    private suspend fun onMethodCallAsync(call: MethodCall, result: MethodChannel.Result) {
        val controls = this.controls
        val mediaController = this.mediaController
        if (controls == null || mediaController == null) {
            connectionIndicator.await()
        }
        if (controls == null || mediaController == null) {
            result.error("-1", "controls is not available", -1)
            return
        }
        val r: Any? = when (call.method) {
            "init" -> controllerCallback.getInitData()
            /*transport controller*/
            "skipToNext" -> controls.skipToNext()
            "skipToPrevious" -> controls.skipToPrevious()
            "pause" -> controls.pause()
            "play" -> controls.play()
            "playFromMediaId", "prepareFromMediaId" -> {
                val mediaId = call.arguments<String>()
                if (call.method == "prepareFromMediaId") {
                    controls.prepareFromMediaId(mediaId, null)
                } else {
                    controls.playFromMediaId(mediaId, null)
                }
            }
            "seekTo" -> controls.seekTo((call.arguments as Number).toLong())
            "setPlayMode" -> controls.setPlayMode(PlayMode.from(call.arguments()))
            /*media controller*/
            "isSessionReady" -> mediaController.isSessionReady
            "getPlaybackState" -> mediaController.playbackState.toMap()
            "getQueue" -> mediaController.queue.map { it.description.toMap() }
            "getQueueTitle" -> mediaController.queueTitle
            "getPlaybackInfo" -> mediaController.playbackInfo.toString() //TODO
            "updatePlayList" -> {
                val playList = call.argument<List<Map<String, Any>>>("queue")!!.map { it.toMediaMetadataCompat() }
                val title = call.argument<String>("queueTitle")
                val queueId = call.argument<String>("queueId")!!
                PlayListExt.updatePlayList(mediaController, playList, title, queueId)
            }
            /* addition */
            "getPrevious" -> {
            }
            "getNext" -> {

            }
            else -> {
                result.notImplemented()
                return
            }
        }
        result.success(if (r == Unit) null else r)
    }

    private fun disconnect() {
        mediaController?.unregisterCallback(controllerCallback)
        mediaController = null

        mediaBrowser.unsubscribe(mediaBrowser.root)
        mediaBrowser.disconnect()
    }

    private fun connect() {
        log { "connect" }
        mediaBrowser.connect()
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {

        fun getInitData(): Map<String, *>? {
            val mediaController = mediaController ?: return null
            val extras = mediaController.extras
            extras.classLoader = MusicPlayerPlugin::class.java.classLoader
            return hashMapOf(
                    "metadata" to mediaController.metadata?.toMap(),
                    "playbackInfo" to null,
                    "playbackState" to mediaController.playbackState?.toMap(),
                    "queueId" to extras.getString(PlayList.KEY_PLAYLIST_ID),
                    "queue" to extras.getParcelableArrayList<MediaMetadataCompat>(PlayList.KEY_PLAYLIST)?.map { it.toMap() },
                    "queueTitle" to mediaController.queueTitle
            )
        }


        override fun onSessionReady() {
            channel.invokeMethod("onSessionReady", null)
        }

        override fun onPlaybackStateChanged(state: PlaybackStateCompat) {
            channel.invokeMethod("onPlaybackStateChanged", state.toMap())
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            channel.invokeMethod("onMetadataChanged", metadata.toMap())
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            channel.invokeMethod("onRepeatModeChanged", repeatMode)
        }

        override fun onShuffleModeChanged(shuffleMode: Int) {
            channel.invokeMethod("onShuffleModeChanged", shuffleMode)
        }

        override fun onQueueChanged(queue: MutableList<MediaSessionCompat.QueueItem>?) {
            // No need to update dart value
        }

        override fun onAudioInfoChanged(info: MediaControllerCompat.PlaybackInfo) {
            channel.invokeMethod("onAudioInfoChanged", null)
        }

        override fun onSessionDestroyed() {
            channel.invokeMethod("onSessionDestroyed", null)
        }

        override fun onQueueTitleChanged(title: CharSequence?) {
            channel.invokeMethod("onQueueTitleChanged", title.toString())
        }

    }
}
