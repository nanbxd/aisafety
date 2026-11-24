package kz.safetyhack.audiotester

import android.content.Context
import android.graphics.drawable.PictureDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.caverock.androidsvg.SVG

/** Shared replaceable assets; absent or invalid Lottie compositions retain their SVG. */
class CallAvatar(context: Context) : FrameLayout(context) {
    private val fallback = ImageView(context).apply {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val player = LottieAnimationView(context).apply {
        repeatCount = LottieDrawable.INFINITE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val manifest = runCatching { context.assets.open("animations/manifest.json").bufferedReader().use { org.json.JSONObject(it.readText()) } }.getOrDefault(org.json.JSONObject())
    private var current = ""
    private var generation = 0
    init {
        addView(fallback, LayoutParams(-1, -1, Gravity.CENTER))
        addView(player, LayoutParams(-1, -1, Gravity.CENTER))
    }
    fun showState(state: String) {
        if (state == current) return
        current = state
        val ticket = ++generation
        player.cancelAnimation()
        player.visibility = View.INVISIBLE
        fallback.visibility = View.VISIBLE
        fallback.setImageDrawable(PictureDrawable(SVG.getFromAsset(context.assets, "icons/face-$state.svg").renderToPicture()))
        player.removeAllLottieOnCompositionLoadedListener()
        player.setImageDrawable(null)
        player.setFailureListener { if (ticket == generation) { player.visibility = View.INVISIBLE; fallback.visibility = View.VISIBLE } }
        val file = manifest.optString(state)
        if (!Regex("[a-zA-Z0-9_-]+\\.json").matches(file) || context.assets.list("animations")?.contains(file) != true) return
        player.addLottieOnCompositionLoadedListener {
            if (ticket == generation) {
                fallback.visibility = View.INVISIBLE
                player.visibility = View.VISIBLE
                val motion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
                if (motion && isShown) player.playAnimation() else player.progress = 0f
            }
        }
        player.setAnimation("animations/$file")
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) player.pauseAnimation()
        else if (Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f) player.resumeAnimation()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) player.pauseAnimation()
        else if (player.composition != null && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f) player.resumeAnimation()
    }
    override fun onDetachedFromWindow() { player.pauseAnimation(); super.onDetachedFromWindow() }
}
