package com.cocos.gamestudio

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

object GameCoverStyler {
    fun apply(container: View, game: GameEntry, cornerRadius: Float) {
        val base = game.iconColor
        val colors = intArrayOf(
            blend(base, Color.WHITE, 0.18f),
            base,
            blend(base, Color.BLACK, 0.22f),
        )
        container.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setStroke(1, withAlpha(Color.WHITE, 0.36f))
        }
    }

    fun orientationLabel(context: Context, rawOrientation: String): String {
        return when (GameOrientation.normalize(rawOrientation) ?: GameOrientation.LANDSCAPE) {
            GameOrientation.PORTRAIT -> context.getString(R.string.game_orientation_portrait)
            else -> context.getString(R.string.game_orientation_landscape)
        }
    }

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val inverse = 1f - ratio
        return Color.rgb(
            (Color.red(from) * inverse + Color.red(to) * ratio).toInt(),
            (Color.green(from) * inverse + Color.green(to) * ratio).toInt(),
            (Color.blue(from) * inverse + Color.blue(to) * ratio).toInt(),
        )
    }

    private fun withAlpha(color: Int, alphaRatio: Float): Int {
        return Color.argb(
            (255 * alphaRatio).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }
}
