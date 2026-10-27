package xie.fa.gram.helpers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import xie.fa.gram.InuConfig
// #if PLUGINS
import xie.fa.gram.helpers.plugins.PluginManager
// #endif
import xie.fa.gram.helpers.security.ParanoiaHelper
import xie.fa.gram.helpers.security.PasscodeHelper
import xie.fa.gram.ui.AccountPickerActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.AlertsCreator
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.LaunchActivity

// Fork-owned launcher (long-press) shortcuts. Re-applied on every stock buildShortcuts() pass, since that
// wipes all dynamic shortcuts. Add an Entry here to expose a new one.
// Stock ranks its own: 0 = compose, 1+ = top chats. Launchers only render the first few by rank.
object ShortcutHelper {
    const val SWITCH_ACCOUNT_ACTION = "xie.fa.gram.action.SWITCH_ACCOUNT"

    private class Entry(
        val id: String,
        val action: String,
        val labelRes: Int,
        val longLabelRes: Int = labelRes,
        val iconRes: Int,
        val rank: Int,
        val target: Class<out Activity> = LaunchActivity::class.java,
        val requiresUnlocked: Boolean = false,
        val shouldShow: () -> Boolean,
        val onClick: (LaunchActivity) -> Unit,
    )

    private val entries = listOf(
        Entry(
            id = "inu_enter_paranoia",
            action = "xie.fa.gram.action.ENTER_PARANOIA",
            labelRes = R.string.InuParanoiaMode,
            iconRes = R.drawable.inu_shortcut_paranoia,
            rank = 0,
            shouldShow = ParanoiaHelper::shouldShowLauncherShortcut,
            onClick = { activity ->
                if (!ParanoiaHelper.isParanoia() && ParanoiaHelper.canUseLauncherShortcut()) {
                    ParanoiaHelper.enableParanoia(activity)
                }
            },
        ),
        Entry(
            id = "inu_switch_account",
            action = SWITCH_ACCOUNT_ACTION,
            labelRes = R.string.InuAccountSwitchShortcut,
            iconRes = R.drawable.inu_shortcut_switch_account,
            rank = 1,
            target = AccountPickerActivity::class.java,
            requiresUnlocked = true,
            shouldShow = { InuConfig.ACCOUNT_SWITCH_SHORTCUT.value && countSelectableAccounts() > 1 },
            onClick = { activity -> showAccountPicker(activity) },
        ),
        // #if PLUGINS
        Entry(
            id = "inu_plugins_safe_mode",
            action = PluginManager.SAFE_MODE_ACTION,
            labelRes = R.string.InuPluginsSafeMode,
            longLabelRes = R.string.InuPluginsSafeModeShortcut,
            iconRes = R.drawable.inu_shortcut_safe_mode,
            rank = 2,
            shouldShow = PluginManager::isEngineEnabled,
            onClick = { PluginManager.requestSafeMode() },
        ),
        // #endif
    )

    @JvmStatic
    fun sync(context: Context) {
        for (entry in entries) {
            if (entry.shouldShow()) {
                val intent = Intent(context, entry.target).setAction(entry.action)
                val shortcut = ShortcutInfoCompat.Builder(context, entry.id)
                    .setShortLabel(getString(entry.labelRes))
                    .setLongLabel(getString(entry.longLabelRes))
                    .setIcon(IconCompat.createWithResource(context, entry.iconRes))
                    .setRank(entry.rank)
                    .setIntent(intent)
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            } else {
                ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(entry.id))
            }
        }
    }

    @JvmStatic
    fun handleAction(activity: LaunchActivity, intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        val entry = entries.firstOrNull { it.action == action } ?: return false
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return true
        // returning false lets stock stash the intent and replay handleIntent() once unlocked,
        // instead of us drawing over the passcode screen.
        if (entry.requiresUnlocked && (AndroidUtilities.needShowPasscode() || SharedConfig.isWaitingForPasscodeEnter)) {
            return false
        }
        entry.onClick(activity)
        return true
    }

    // adaptive icons show only the central 72/108 of the bitmap, the rest is bleed for masks and parallax
    @JvmStatic
    fun createAvatarIcon(photo: Bitmap?, user: TLRPC.User?, chat: TLRPC.Chat?): IconCompat {
        val serviceType = when {
            user == null -> AvatarDrawable.AVATAR_TYPE_NORMAL
            UserObject.isReplyUser(user) -> AvatarDrawable.AVATAR_TYPE_REPLIES
            UserObject.isUserSelf(user) -> AvatarDrawable.AVATAR_TYPE_SAVED
            else -> AvatarDrawable.AVATAR_TYPE_NORMAL
        }
        val avatar = if (photo != null && serviceType == AvatarDrawable.AVATAR_TYPE_NORMAL) {
            photo
        } else {
            val placeholderSize = AndroidUtilities.dp(58f)
            val drawable = (if (user != null) AvatarDrawable(user) else AvatarDrawable(chat)).apply {
                if (serviceType != AvatarDrawable.AVATAR_TYPE_NORMAL) avatarType = serviceType
                setRoundRadius(1)
                setBounds(0, 0, placeholderSize, placeholderSize)
            }
            Bitmap.createBitmap(placeholderSize, placeholderSize, Bitmap.Config.ARGB_8888).also { drawable.draw(Canvas(it)) }
        }

        val size = AndroidUtilities.dp(108f)
        val inset = size / 6f
        val matrix = Matrix().apply {
            setRectToRect(
                RectF(0f, 0f, avatar.width.toFloat(), avatar.height.toFloat()),
                RectF(inset, inset, size - inset, size - inset),
                Matrix.ScaleToFit.FILL,
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(avatar, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        }
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(result).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        return IconCompat.createWithAdaptiveBitmap(result)
    }

    fun countSelectableAccounts(): Int =
        (0 until UserConfig.MAX_ACCOUNT_COUNT).count {
            UserConfig.getInstance(it).currentUser != null && !PasscodeHelper.isAccountHidden(it)
        }

    // in-app fallback, used when AccountPickerActivity handed off because a passcode is set. the app is
    // already resuming here, so the account we're about to leave would otherwise report itself online.
    private fun showAccountPicker(activity: LaunchActivity) {
        if (countSelectableAccounts() < 2) return
        val previous = UserConfig.selectedAccount
        val controller = MessagesController.getInstance(previous)
        controller.ignoreSetOnline = true

        val dialog = AlertsCreator.createAccountSelectDialog(activity) { account ->
            controller.ignoreSetOnline = false
            if (account != previous) activity.switchToAccount(account, true)
        }
        if (dialog == null) {
            controller.ignoreSetOnline = false
            return
        }
        dialog.setOnDismissListener { controller.ignoreSetOnline = false }
        dialog.show()
    }
}
