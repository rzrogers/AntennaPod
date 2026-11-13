package de.danoeh.antennapod.wearos

import androidx.annotation.DrawableRes
import de.danoeh.antennapod.ui.notifications.R as NotificationsR
import kotlin.math.abs

object PlusJumpIcons {
    private val seconds = intArrayOf(5, 10, 15, 20, 30, 45, 60)
    private val back = intArrayOf(
        NotificationsR.drawable.ic_notification_jump_back_5,
        NotificationsR.drawable.ic_notification_jump_back_10,
        NotificationsR.drawable.ic_notification_jump_back_15,
        NotificationsR.drawable.ic_notification_jump_back_20,
        NotificationsR.drawable.ic_notification_jump_back_30,
        NotificationsR.drawable.ic_notification_jump_back_45,
        NotificationsR.drawable.ic_notification_jump_back_60
    )
    private val forward = intArrayOf(
        NotificationsR.drawable.ic_notification_jump_forward_5,
        NotificationsR.drawable.ic_notification_jump_forward_10,
        NotificationsR.drawable.ic_notification_jump_forward_15,
        NotificationsR.drawable.ic_notification_jump_forward_20,
        NotificationsR.drawable.ic_notification_jump_forward_30,
        NotificationsR.drawable.ic_notification_jump_forward_45,
        NotificationsR.drawable.ic_notification_jump_forward_60
    )

    @DrawableRes
    fun back(value: Int): Int = back[indexOf(value)]

    @DrawableRes
    fun forward(value: Int): Int = forward[indexOf(value)]

    private fun indexOf(value: Int): Int = seconds.indices.minByOrNull { abs(seconds[it] - value) } ?: 0
}
