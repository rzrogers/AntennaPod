package de.danoeh.antennapod.wearos

import android.os.SystemClock
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import de.danoeh.antennapod.ui.common.R as CommonR
import de.danoeh.antennapod.wearos.sync.PlusWearRepository
import de.danoeh.antennapod.wearos.sync.WearMessageSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PlaylistTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val now = SystemClock.elapsedRealtime()
        if (lastRequest == 0L || now - lastRequest > REFRESH_MILLIS) {
            lastRequest = now
            scope.launch { WearMessageSender.send(applicationContext, PlusWear.PLAYLISTS) }
        }
        val rows = PlusWearRepository.cachedPlaylists(this)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_MILLIS)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(rows)))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(IMAGE_APP, image(CommonR.mipmap.ic_launcher_foreground))
            .addIdToImageMapping(IMAGE_PHONE, image(CommonR.drawable.ic_phone_black))
            .build()
        return Futures.immediateFuture(resources)
    }

    private fun image(resId: Int): ResourceBuilders.ImageResource = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(
            ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(resId).build()
        )
        .build()

    private fun layout(rows: List<PlusWear.PlaylistRow>): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(getString(CommonR.string.playlists_label), 13f, COLOR_LABEL))
            .addContent(spacer(4f))
        if (rows.isEmpty()) {
            column.addContent(text(getString(CommonR.string.wearos_no_items), 13f, COLOR_SECONDARY))
            column.addContent(spacer(6f))
        }
        for (row in rows.take(MAX_PLAYLISTS)) {
            column.addContent(chip(row))
            column.addContent(spacer(3f))
        }
        column.addContent(spacer(2f))
        column.addContent(
            LayoutElementBuilders.Row.Builder()
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(circleButton(IMAGE_APP, COLOR_ACCENT, null, openAppClickable(), 44f))
                .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(16f)).build())
                .addContent(
                    circleButton(IMAGE_PHONE, COLOR_CHIP, COLOR_TEXT, actionClickable(ACTION_OPEN_PHONE, null), 24f)
                )
                .build()
        )
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column.build())
            .build()
    }

    private fun chip(row: PlusWear.PlaylistRow): LayoutElementBuilders.LayoutElement {
        val modifiers = ModifiersBuilders.Modifiers.Builder()
            .setBackground(
                ModifiersBuilders.Background.Builder()
                    .setColor(argb(COLOR_CHIP))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(18f)).build())
                    .build()
            )
            .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(14f)).setEnd(dp(14f)).build())
        if (row.playable) {
            modifiers.setClickable(actionClickable(ACTION_PLAY_PREFIX + row.id, row.id))
        }
        val textColor = if (row.playable) COLOR_TEXT else COLOR_SECONDARY
        return LayoutElementBuilders.Box.Builder()
            .setWidth(dp(CHIP_WIDTH))
            .setHeight(dp(36f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(modifiers.build())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                    .addContent(text(row.title, 14f, textColor))
                    .addContent(text(row.subtitle, 10f, COLOR_SECONDARY))
                    .build()
            )
            .build()
    }

    private fun circleButton(
        imageId: String,
        background: Int,
        tint: Int?,
        clickable: ModifiersBuilders.Clickable,
        imageSize: Float
    ): LayoutElementBuilders.LayoutElement {
        val image = LayoutElementBuilders.Image.Builder()
            .setResourceId(imageId)
            .setWidth(dp(imageSize))
            .setHeight(dp(imageSize))
        if (tint != null) {
            image.setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(argb(tint)).build())
        }
        return LayoutElementBuilders.Box.Builder()
            .setWidth(dp(44f))
            .setHeight(dp(44f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(background))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(22f)).build())
                            .build()
                    )
                    .build()
            )
            .addContent(image.build())
            .build()
    }

    private fun text(value: String, size: Float, color: Int): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(1)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .build()
            )
            .build()

    private fun spacer(height: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

    private fun openAppClickable(): ModifiersBuilders.Clickable = ModifiersBuilders.Clickable.Builder()
        .setId("open_app")
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(packageName)
                        .setClassName(MainListActivity::class.java.name)
                        .build()
                )
                .build()
        )
        .build()

    private fun actionClickable(action: String, id: Long?): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId(if (id != null) "play_$id" else action)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(TileActionActivity::class.java.name)
                            .addKeyToExtraMapping(
                                TileActionActivity.EXTRA_ACTION,
                                ActionBuilders.AndroidStringExtra.Builder().setValue(action).build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val ACTION_PLAY_PREFIX = "play:"
        const val ACTION_OPEN_PHONE = "open_phone"
        private const val RESOURCES_VERSION = "1"
        private const val IMAGE_APP = "app"
        private const val IMAGE_PHONE = "phone"
        private const val MAX_PLAYLISTS = 3
        private const val CHIP_WIDTH = 150f
        private const val FRESHNESS_MILLIS = 15 * 60 * 1000L
        private const val REFRESH_MILLIS = 60 * 1000L
        private val COLOR_CHIP = 0xFF2E3440.toInt()
        private val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private val COLOR_SECONDARY = 0xFFB4B9C4.toInt()
        private val COLOR_LABEL = 0xFF9FC9F0.toInt()
        private val COLOR_ACCENT = 0xFF0078C2.toInt()
        private var lastRequest = 0L
    }
}
