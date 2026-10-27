package xie.fa.gram.helpers

import android.graphics.Canvas
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import xie.fa.gram.InuConfig

object StatusIndicatorHelper {

    @JvmStatic
    fun getOnlineColor(user: TLRPC.User?, resourcesProvider: Theme.ResourcesProvider?): Int {
        if (!InuConfig.AVATAR_ONLINE_STATUS.value) return 0
        if (user == null || user.status == null || user.bot == true || user.self == true) return 0
        val currentTime = ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTime()

        if (user.status.expires <= 0) {
            val allowed = MessagesController.getInstance(UserConfig.selectedAccount)
                .onlinePrivacy.containsKey(user.id)
            if (allowed) {
                return Theme.getColor(Theme.key_chats_onlineCircle, resourcesProvider)
            }
            return 0
        }

        val diff = user.status.expires - currentTime
        return when {
            diff > 0 -> Theme.getColor(Theme.key_chats_onlineCircle, resourcesProvider)
            diff > -15 * 60 -> Theme.getColor(Theme.key_chats_onlineCircleRecencyTier1, resourcesProvider)
            diff > -30 * 60 -> Theme.getColor(Theme.key_chats_onlineCircleRecencyTier2, resourcesProvider)
            diff > -60 * 60 -> Theme.getColor(Theme.key_chats_onlineCircleRecencyTier3, resourcesProvider)
            else -> 0
        }
    }

    // Draws the presence dot (white ring + colored inner dot) at the bottom-right corner
    // of a message avatar. Returns true when the overlay was drawn; callers should draw
    // the plain avatar when it returns false.
    @JvmStatic
    fun drawAvatarOnlineDot(
        canvas: Canvas,
        imageReceiver: ImageReceiver,
        user: TLRPC.User?,
        resourcesProvider: Theme.ResourcesProvider?
    ): Boolean {
        val color = getOnlineColor(user, resourcesProvider)
        if (color == 0) return false
        imageReceiver.draw(canvas)
        val cx = imageReceiver.imageX2 - AndroidUtilities.dp(7f)
        val cy = imageReceiver.imageY2 - AndroidUtilities.dp(7f)
        val paint = Theme.dialogs_onlineCirclePaint
        paint.color = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)
        canvas.drawCircle(cx, cy, AndroidUtilities.dp(7f).toFloat(), paint)
        paint.color = color
        canvas.drawCircle(cx, cy, AndroidUtilities.dp(5f).toFloat(), paint)
        return true
    }

}
