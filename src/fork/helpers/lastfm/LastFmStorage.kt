package xie.fa.gram.helpers.lastfm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.UserConfig

object LastFmStorage {
    private const val TAG = "LastFmStorage"
    private const val PREFS_FILE = "lastfm_secure_prefs"
    private const val FALLBACK_PREFS_FILE = "lastfm_plain_prefs"

    private const val KEY_USERNAME = "lastfm_username"
    private const val KEY_SHOW_ON_PROFILE = "lastfm_show_on_profile"
    private const val KEY_USE_OWN_KEY = "lastfm_use_own_key"
    private const val KEY_PERSONAL_API_KEY = "lastfm_personal_api_key"

    private val prefsMap = ConcurrentHashMap<Int, SharedPreferences>()

    private fun getPrefsName(account: Int): String =
        if (account == 0) PREFS_FILE else "${PREFS_FILE}_$account"

    private fun getFallbackPrefsName(account: Int): String =
        if (account == 0) FALLBACK_PREFS_FILE else "${FALLBACK_PREFS_FILE}_$account"

    private fun getPrefs(context: Context, account: Int): SharedPreferences {
        prefsMap[account]?.let { return it }
        synchronized(this) {
            prefsMap[account]?.let { return it }
            val fileName = getPrefsName(account)
            val fallbackName = getFallbackPrefsName(account)
            val p = try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    fileName,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize EncryptedSharedPreferences for account $account, falling back to plain", e)
                context.getSharedPreferences(fallbackName, Context.MODE_PRIVATE)
            }
            prefsMap[account] = p
            return p
        }
    }

    private fun safePrefs(account: Int): SharedPreferences =
        getPrefs(ApplicationLoader.applicationContext, account)

    fun getUsername(account: Int): String =
        safePrefs(account).getString(KEY_USERNAME, "") ?: ""

    fun setUsername(account: Int, value: String) {
        safePrefs(account).edit().putString(KEY_USERNAME, value.trim()).apply()
    }

    fun isShowOnProfile(account: Int): Boolean =
        safePrefs(account).getBoolean(KEY_SHOW_ON_PROFILE, true)

    fun setShowOnProfile(account: Int, value: Boolean) {
        safePrefs(account).edit().putBoolean(KEY_SHOW_ON_PROFILE, value).apply()
    }

    fun isUseOwnApiKey(account: Int): Boolean =
        safePrefs(account).getBoolean(KEY_USE_OWN_KEY, false)

    fun setUseOwnApiKey(account: Int, value: Boolean) {
        safePrefs(account).edit().putBoolean(KEY_USE_OWN_KEY, value).apply()
    }

    fun getPersonalApiKey(account: Int): String =
        safePrefs(account).getString(KEY_PERSONAL_API_KEY, "") ?: ""

    fun setPersonalApiKey(account: Int, value: String) {
        safePrefs(account).edit().putString(KEY_PERSONAL_API_KEY, value.trim()).apply()
    }

    @JvmStatic
    fun resolveApiKey(account: Int, isSelf: Boolean): String {
        if (isSelf && isUseOwnApiKey(account)) {
            val key = getPersonalApiKey(account).trim()
            if (key.isNotEmpty()) {
                return key
            }
        }
        return BuildConfig.LASTFM_API_KEY ?: ""
    }

    // Overloads defaulting to UserConfig.selectedAccount
    var username: String
        get() = getUsername(UserConfig.selectedAccount)
        set(value) = setUsername(UserConfig.selectedAccount, value)

    var showOnProfile: Boolean
        get() = isShowOnProfile(UserConfig.selectedAccount)
        set(value) = setShowOnProfile(UserConfig.selectedAccount, value)

    var useOwnApiKey: Boolean
        get() = isUseOwnApiKey(UserConfig.selectedAccount)
        set(value) = setUseOwnApiKey(UserConfig.selectedAccount, value)

    var personalApiKey: String
        get() = getPersonalApiKey(UserConfig.selectedAccount)
        set(value) = setPersonalApiKey(UserConfig.selectedAccount, value)

    @JvmStatic
    fun resolveApiKey(isSelf: Boolean): String =
        resolveApiKey(UserConfig.selectedAccount, isSelf)
}
