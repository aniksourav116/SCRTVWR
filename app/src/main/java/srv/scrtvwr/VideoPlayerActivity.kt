package srv.scrtvwr

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

class VideoPlayerActivity : ComponentActivity() {
    private var videoView: VideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
        if (videoPath.isNullOrBlank()) {
            finish()
            return
        }

        val playerView = VideoView(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        videoView = playerView

        val controller = MediaController(this).apply {
            setAnchorView(playerView)
        }
        playerView.setMediaController(controller)
        playerView.setVideoPath(videoPath)
        playerView.setOnPreparedListener {
            controller.show()
            playerView.start()
        }
        playerView.setOnCompletionListener {
            controller.show()
        }

        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(playerView)
            },
        )
    }

    override fun onPause() {
        super.onPause()
        videoView?.pause()
    }

    override fun onDestroy() {
        videoView?.stopPlayback()
        videoView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VIDEO_PATH = "srv.scrtvwr.extra.VIDEO_PATH"
    }
}
