package ru.usedesk.chat_gui.chat

import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import ru.usedesk.chat_gui.IUsedeskMediaPlayerAdapter
import ru.usedesk.chat_gui.R
import ru.usedesk.common_gui.hideKeyboard
import ru.usedesk.common_gui.visibleGone
import ru.usedesk.common_gui.visibleInvisible

class UsedeskMediaPlayerAdapter(
    activity: AppCompatActivity,
    private val lFullscreen: ViewGroup,
    private val onFullscreenMode: (Boolean) -> Unit,
    private val onDownload: (String, String) -> Unit
) : IUsedeskMediaPlayerAdapter {

    private val playerViewModel: PlayerViewModel by activity.viewModels()

    private val pvVideoExoPlayer = lFullscreen.findViewById<PlayerView>(R.id.pv_video)

    private val pvAudioExoPlayer = lFullscreen.findViewById<PlayerView>(R.id.pv_audio)

    private var restored = true

    private val exoPlayer: ExoPlayer =
        playerViewModel.exoPlayer ?: ExoPlayer.Builder(lFullscreen.context)
            .setUseLazyPreparation(true)
            .build().also {
                playerViewModel.exoPlayer = it
                restored = false
            }

    private var lastMinimizeView: MinimizeView? = null
    private var currentMinimizeView: MinimizeView? = null

    private val videoExoPlayerViews = VideoExoPlayerViews(pvVideoExoPlayer)
    private val audioExoPlayerViews = AudioExoPlayerViews(pvAudioExoPlayer)

    init {
        activity.lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {
                        onPause()
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        onResume()
                    }
                }
            }
        })

        audioExoPlayerViews.contentFrame.updateLayoutParams {
            width = 0
            height = 0
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                videoExoPlayerViews.pbLoading.visibility =
                    visibleInvisible(playbackState == Player.STATE_BUFFERING)
            }
        })

        pvVideoExoPlayer.setControllerVisibilityListener { visibility ->
            val visible = visibility == View.VISIBLE
            videoExoPlayerViews.controls.startAnimation(
                AnimationUtils.loadAnimation(
                    lFullscreen.context,
                    if (visible) {
                        R.anim.fade_in
                    } else {
                        R.anim.fade_out
                    }
                )
            )
            currentMinimizeView?.doOnControlsVisibilityChanged?.invoke(visible)
        }

        videoExoPlayerViews.ivDownload.setOnClickListener {
            val model = playerViewModel.modelLiveData.value
            onDownload(model.key, model.name)
        }

        audioExoPlayerViews.tvDownload.setOnClickListener {
            val model = playerViewModel.modelLiveData.value
            onDownload(model.key, model.name)
        }

        videoExoPlayerViews.fullscreenButton.setOnClickListener {
            playerViewModel.fullscreen()
        }

        playerViewModel.modelLiveData.initAndObserveWithOld(activity) { old, new ->
            if (!restored && old?.mode != new.mode) {
                resetPlayer()
            }
            if (new.key.isNotEmpty()) {
                if (!restored && old?.key != new.key) {
                    when (new.mode) {
                        PlayerViewModel.Mode.VIDEO_EXO_PLAYER,
                        PlayerViewModel.Mode.AUDIO_EXO_PLAYER -> {
                            exoPlayer.run {
                                setMediaItem(MediaItem.fromUri(new.key))
                                exoPlayer.prepare()
                                exoPlayer.play()
                            }
                            if (new.mode == PlayerViewModel.Mode.VIDEO_EXO_PLAYER) {
                                pvVideoExoPlayer.hideController()
                            } else {
                                pvAudioExoPlayer.showController()
                            }
                            changeFullscreen(false)
                        }
                    }
                }//Фулскрин обрабатываем только если не был обновлён videoKey
                else if (old?.fullscreen != new.fullscreen) {
                    changeFullscreen(new.fullscreen)
                    if (old?.fullscreen == null && restored && playerViewModel.lastPlaying) {
                        exoPlayer.play()
                    }
                }
            }
        }
    }

    fun download(mediaKey: String, name: String) {
        onDownload(mediaKey, name)
    }

    private fun onPause() {
        when (playerViewModel.modelLiveData.value.mode) {
            PlayerViewModel.Mode.VIDEO_EXO_PLAYER,
            PlayerViewModel.Mode.AUDIO_EXO_PLAYER -> {
                playerViewModel.lastPlaying = exoPlayer.isPlaying
                exoPlayer.pause()
            }
        }
    }

    private fun onResume() {
        val model = playerViewModel.modelLiveData.value
        if (model.fullscreen &&
            (model.mode == PlayerViewModel.Mode.VIDEO_EXO_PLAYER)
        ) {
            onFullscreenMode(true)
        }
        if (playerViewModel.lastPlaying) {
            exoPlayer.play()
        }
    }

    private fun changeFullscreen(fullscreen: Boolean) {
        when (playerViewModel.modelLiveData.value.mode) {
            PlayerViewModel.Mode.VIDEO_EXO_PLAYER -> {
                (pvVideoExoPlayer.parent as? ViewGroup)?.removeView(pvVideoExoPlayer)

                if (fullscreen) {
                    lFullscreen.addView(pvVideoExoPlayer)
                    videoExoPlayerViews.fullscreenButton.setImageResource(R.drawable.exo_ic_fullscreen_exit)
                } else {
                    currentMinimizeView?.lVideoMinimized?.addView(pvVideoExoPlayer)
                    videoExoPlayerViews.fullscreenButton.setImageResource(R.drawable.exo_ic_fullscreen_enter)
                }
                //Каждый раз задаём плеер, иначе он не поймёт что только что произошло
                pvVideoExoPlayer.player = exoPlayer
                lFullscreen.visibility = visibleGone(fullscreen)
                onFullscreenMode(fullscreen)
            }
            PlayerViewModel.Mode.AUDIO_EXO_PLAYER -> {
                (pvAudioExoPlayer.parent as? ViewGroup)?.removeView(pvAudioExoPlayer)

                if (fullscreen) {
                    lFullscreen.addView(pvAudioExoPlayer)
                } else {
                    currentMinimizeView?.lVideoMinimized?.addView(pvAudioExoPlayer)
                }
                //Каждый раз задаём плеер, иначе он не поймёт что только что произошло
                pvAudioExoPlayer.player = exoPlayer
                lFullscreen.visibility = visibleGone(fullscreen)
                onFullscreenMode(fullscreen)
                pvAudioExoPlayer.showController()
            }
        }
    }

    override fun onBackPressed(): Boolean = playerViewModel.onBackPressed()

    override fun attachPlayer(
        lMinimized: ViewGroup,
        mediaKey: String,
        mediaName: String,
        playerType: IUsedeskMediaPlayerAdapter.PlayerType,
        onCancel: () -> Unit,
        onControlsVisibilityChanged: ((Boolean) -> Unit)
    ) {
        hideKeyboard(lFullscreen)

        //Текущий плеер станет старым
        lastMinimizeView = currentMinimizeView

        //Сбросим старое воспроизведение, если оно было
        resetPlayer()

        //Сохраним данные для нового воспроизведения
        currentMinimizeView =
            MinimizeView(lMinimized, onCancel, onControlsVisibilityChanged)

        when (playerType) {
            IUsedeskMediaPlayerAdapter.PlayerType.VIDEO -> {
                playerViewModel.videoApply(mediaKey, mediaName)
            }
            IUsedeskMediaPlayerAdapter.PlayerType.AUDIO -> {
                playerViewModel.audioApply(mediaKey, mediaName)
            }
        }
    }

    private fun resetPlayer() {
        exoPlayer.stop()
        pvVideoExoPlayer.player = null
        pvAudioExoPlayer.player = null
        (pvVideoExoPlayer.parent as? ViewGroup)?.removeView(pvVideoExoPlayer)
        (pvAudioExoPlayer.parent as? ViewGroup)?.removeView(pvAudioExoPlayer)
        lastMinimizeView?.release()
        lastMinimizeView = null
        lFullscreen.visibility = View.GONE
    }

    override fun detachPlayer(key: String): Boolean {
        val model = playerViewModel.modelLiveData.value
        return if (model.key == key && !model.fullscreen) {
            lastMinimizeView = currentMinimizeView
            resetPlayer()

            playerViewModel.reset()
            true
        } else {
            false
        }
    }

    /**
     * Вызывается для того, чтобы прикрепиться к адаптеру, для реакции на minimize или переключения
     * плеера
     */
    override fun reattachPlayer(
        lMinimized: ViewGroup,
        mediaKey: String,
        onCancel: () -> Unit,
        onControlsVisibilityChanged: ((Boolean) -> Unit)
    ): Boolean {
        val model = playerViewModel.modelLiveData.value
        return if (model.key == mediaKey) {
            //Сохраним данные для плеера
            currentMinimizeView = MinimizeView(
                lMinimized,
                onCancel,
                onControlsVisibilityChanged
            )

            if (model.fullscreen) {
                changeFullscreen(true)
            } else {
                changeFullscreen(false)
            }
            true
        } else {
            false
        }
    }

    private class VideoExoPlayerViews(exoPlayerView: PlayerView) {
        val fullscreenButton = exoPlayerView.findViewById<ImageView>(R.id.exo_fullscreen_icon)
        val controls = exoPlayerView.findViewById<View>(R.id.exo_controller)
        val pbLoading = exoPlayerView.findViewById<ProgressBar>(R.id.loading)
        val ivDownload = exoPlayerView.findViewById<View>(R.id.iv_download)
    }

    private class AudioExoPlayerViews(exoPlayerView: PlayerView) {
        val contentFrame = exoPlayerView.findViewById<View>(R.id.exo_content_frame)
        val tvDownload = exoPlayerView.findViewById<View>(R.id.tv_download)
    }

    private class MinimizeView(
        val lVideoMinimized: ViewGroup,
        val doOnCancelPlay: () -> Unit,
        val doOnControlsVisibilityChanged: (Boolean) -> Unit
    ) {
        fun release() {
            doOnControlsVisibilityChanged.invoke(false)
            doOnCancelPlay.invoke()
        }
    }
}