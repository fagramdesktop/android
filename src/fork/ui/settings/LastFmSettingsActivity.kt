package xie.fa.gram.ui.settings

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import xie.fa.gram.SearchRegistry
import xie.fa.gram.helpers.InuUtils
import xie.fa.gram.helpers.lastfm.LastFmBioSyncHelper
import xie.fa.gram.helpers.lastfm.LastFmStorage

class LastFmSettingsActivity() : SettingsPageActivity() {

    constructor(account: Int) : this() {
        setCurrentAccount(account)
    }

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuLastFm)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuLastFm)))

        val currentUsername = LastFmStorage.getUsername(currentAccount)
        items.add(
            UItem.asButton(
                BUTTON_USERNAME,
                LocaleController.getString(R.string.InuLastFmUsername),
                if (currentUsername.isNotEmpty()) currentUsername else LocaleController.getString(R.string.None)
            )
        )

        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SHOW_ON_PROFILE,
                R.string.InuLastFmShowOnProfile,
                R.string.InuLastFmShowOnProfileInfo,
                LastFmStorage.isShowOnProfile(currentAccount)
            )
        )

        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuExperimental)))

        items.add(
            mkTwoLineCheckItem(
                TOGGLE_USE_OWN_KEY,
                R.string.InuLastFmUseOwnApiKey,
                R.string.InuLastFmUseOwnApiKeyInfo,
                LastFmStorage.isUseOwnApiKey(currentAccount)
            )
        )

        if (LastFmStorage.isUseOwnApiKey(currentAccount)) {
            val key = LastFmStorage.getPersonalApiKey(currentAccount)
            items.add(
                UItem.asButton(
                    BUTTON_PERSONAL_KEY,
                    LocaleController.getString(R.string.InuLastFmPersonalApiKey),
                    if (key.isNotEmpty()) "••••••••" else LocaleController.getString(R.string.None)
                )
            )
        }

        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_USERNAME -> showUsernameDialog()

            TOGGLE_SHOW_ON_PROFILE -> {
                val new = !LastFmStorage.isShowOnProfile(currentAccount)
                LastFmStorage.setShowOnProfile(currentAccount, new)
                (view as? NotificationsCheckCell)?.isChecked = new
                (view as? TextCheckCell)?.isChecked = new
                LastFmBioSyncHelper.onShowOnProfileToggled(currentAccount)
            }

            TOGGLE_USE_OWN_KEY -> {
                val new = !LastFmStorage.isUseOwnApiKey(currentAccount)
                LastFmStorage.setUseOwnApiKey(currentAccount, new)
                listView.adapter.update(true)
            }

            BUTTON_PERSONAL_KEY -> showPersonalKeyDialog()
        }
    }

    private fun showUsernameDialog() {
        val ctx = context ?: return
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(24f), AndroidUtilities.dp(8f), AndroidUtilities.dp(24f), 0)
        }

        val input = EditText(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            hint = LocaleController.getString(R.string.InuLastFmUsername)
            setText(LastFmStorage.getUsername(currentAccount))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            textSize = 16f
        }
        container.addView(input, LinearLayout.LayoutParams(-1, -2))

        AlertDialog.Builder(ctx)
            .setTitle(LocaleController.getString(R.string.InuLastFmUsername))
            .setView(container)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName != LastFmStorage.getUsername(currentAccount)) {
                    LastFmStorage.setUsername(currentAccount, newName)
                    LastFmBioSyncHelper.onUsernameChanged(currentAccount)
                    listView.adapter.update(true)
                }
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    private fun showPersonalKeyDialog() {
        val ctx = context ?: return
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(24f), AndroidUtilities.dp(8f), AndroidUtilities.dp(24f), 0)
        }

        val input = EditText(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            hint = LocaleController.getString(R.string.InuLastFmPersonalApiKey)
            setText(LastFmStorage.getPersonalApiKey(currentAccount))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            textSize = 16f
        }
        container.addView(input, LinearLayout.LayoutParams(-1, -2))

        AlertDialog.Builder(ctx)
            .setTitle(LocaleController.getString(R.string.InuLastFmPersonalApiKey))
            .setView(container)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                val newKey = input.text.toString().trim()
                LastFmStorage.setPersonalApiKey(currentAccount, newKey)
                listView.adapter.update(true)
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    companion object {
        private val BUTTON_USERNAME = InuUtils.generateId()
        private val TOGGLE_SHOW_ON_PROFILE = InuUtils.generateId()
        private val TOGGLE_USE_OWN_KEY = InuUtils.generateId()
        private val BUTTON_PERSONAL_KEY = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "lastfm",
            titleRes = R.string.InuLastFm,
            iconRes = R.drawable.files_music,
            factory = ::LastFmSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("lastfm-username", R.string.InuLastFmUsername, BUTTON_USERNAME),
                SearchRegistry.Entry("lastfm-show-on-profile", R.string.InuLastFmShowOnProfile, TOGGLE_SHOW_ON_PROFILE),
                SearchRegistry.Entry("lastfm-use-own-key", R.string.InuLastFmUseOwnApiKey, TOGGLE_USE_OWN_KEY),
                SearchRegistry.Entry("lastfm-personal-key", R.string.InuLastFmPersonalApiKey, BUTTON_PERSONAL_KEY),
            )
        )
    }
}
