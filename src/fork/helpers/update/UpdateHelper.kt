package xie.fa.gram.helpers.update

import android.os.Build
import android.util.Base64
import xie.fa.gram.InuConfig
import xie.fa.gram.helpers.security.ParanoiaHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BetaUpdate
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.RichMessageLayout
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.SerializedData
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_iv

object UpdateHelper {
    const val USERNAME = "fagramci"
    private const val CHANNEL_ID = 3924906222L
    private const val CHECK_INTERVAL_MS = 4L * 60 * 60 * 1000
    private const val INFLIGHT_TIMEOUT_MS = 60L * 1000

    val packageInfo by lazy {
        ApplicationLoader.applicationContext.packageManager.getPackageInfo(
            ApplicationLoader.applicationContext.packageName,
            0
        )
    }

    @JvmStatic
    val stockVersionName by lazy {
        packageInfo.versionName?.replace(Regex("-[0-9a-f]{7}$"), "") ?: ""
    }

    fun getVersionInfoString(): String {
        return LocaleController.formatString(
            R.string.InuVersion,
            packageInfo.versionCode,
            stockVersionName,
            BuildConfig.STOCK_VERSION_CODE,
            LocaleController.getString(
                if (BuildConfig.INU_PLUGINLESS) R.string.InuBuildPluginless else R.string.InuBuildFull
            )
        )
    }

    fun getAboutVersionString(): String {
        return LocaleController.formatString(
            R.string.InuAboutVersionFormat,
            packageInfo.versionCode,
            stockVersionName,
            BuildConfig.STOCK_VERSION_CODE
        )
    }

    @JvmStatic
    fun getFullVersionInfo(): String {
        if (ParanoiaHelper.isDisguised()) {
            return "Telegram for Android v${stockVersionName} (${BuildConfig.STOCK_VERSION_CODE})\ndirect ${Build.CPU_ABI} ${Build.CPU_ABI2}"
        }
        return "${getVersionInfoString()}\nBuilt on: ${BuildVars.BUILD_DATE}"
    }

    private val APK_RE = Regex("^(?:inugram|fagram)-(.+)-(\\d+)-(full|pluginless)\\.apk$", RegexOption.IGNORE_CASE)
    private val SHORT_SHA_RE = Regex("-([0-9a-f]{7,40})$")
    private val SHA256_RE = Regex("""(?:SHA256|Hash):\s*([a-fA-F0-9]{64})""", RegexOption.IGNORE_CASE)

    @Volatile
    private var inflight = false

    @Volatile
    private var inflightSince = 0L

    @Volatile
    var pendingBetaUpdate: BetaUpdate? = null
        private set

    @Volatile
    var pendingSha256: String? = null
        get() = field ?: InuConfig.UPDATE_PENDING_SHA256.value.ifEmpty { null }
        set(value) {
            field = value
            InuConfig.UPDATE_PENDING_SHA256.value = value ?: ""
        }

    // cached source message of the current pending update, set by applyUpdate. lets
    // startDownload skip the resolver+RPC dance when the update was detected this session.
    @Volatile
    private var pendingSourceMessage: TLRPC.Message? = null

    // true between the click on Update and FileLoader.loadFile actually firing. lets the row
    // show the Downloading state immediately even while the async file-ref refresh dance is
    // still running.
    @Volatile
    var isPendingStart: Boolean = false
        private set

    // applyUpdate doesn't post appUpdateAvailable itself — the caller does it, via
    // revealPendingUpdate, once the changelog dialog is on screen (so the bar slides in behind
    // the dialog instead of visibly popping into the page underneath).
    @JvmStatic
    fun revealPendingUpdate() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable, true)
    }

    fun checkForCustomUpdate(force: Boolean, whenDone: Runnable?) {
        if (!InuConfig.UPDATES_ENABLED.value) {
            whenDone?.run()
            return
        }
        if (!force && System.currentTimeMillis() - InuConfig.UPDATE_LAST_CHECK_MS.value < CHECK_INTERVAL_MS) {
            whenDone?.run()
            return
        }
        check { whenDone?.run() }
    }

    // a pending update saved by an older build carries plain text there
    fun getChangelog(update: TLRPC.TL_help_appUpdate): TL_iv.RichMessage? {
        val bytes = runCatching { Base64.decode(update.text, Base64.NO_WRAP) }.getOrNull() ?: return null
        val data = SerializedData(bytes)
        val changelog = TL_iv.RichMessage.TLdeserialize(data, data.readInt32(false), false)
        data.cleanup()
        return changelog
    }

    fun clearPending() {
        pendingBetaUpdate = null
        pendingSourceMessage = null
        pendingSha256 = null
        isPendingStart = false
        SharedConfig.pendingAppUpdate = null
        SharedConfig.saveConfig()
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable, false)
    }

    @JvmStatic
    fun clearPendingIfInstalled() {
        val pending = SharedConfig.pendingAppUpdate ?: return
        val current = currentBuild()
        if (pending.version == current.versionCode.toString()) {
            clearPending()
        }
    }

    fun startDownload(account: Int) {
        val update = SharedConfig.pendingAppUpdate ?: return
        val doc = update.document ?: return

        isPendingStart = true
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading)

        val cached = pendingSourceMessage
        if (cached != null) {
            beginLoad(account, doc, MessageObject(account, cached, false, false))
            return
        }

        val messageId = update.id
        if (messageId <= 0) {
            refreshPendingAndStart(account)
            return
        }
        val mc = MessagesController.getInstance(account)
        // resolve first so the channel (with access_hash) is cached for getInputChannel
        mc.userNameResolver.resolve(USERNAME) { peerId ->
            AndroidUtilities.runOnUIThread {
                if (!isPendingStart) return@runOnUIThread
                if (peerId == null || peerId == 0L || peerId == Long.MAX_VALUE) {
                    stopPendingStart()
                    return@runOnUIThread
                }
                loadRichMessage(account, messageId) { msg ->
                    if (!isPendingStart) return@loadRichMessage
                    val freshDoc = msg?.let { extractApkInfo(it, currentVariant())?.document }
                    if (msg == null || freshDoc == null) {
                        beginLoad(account, doc, sourceMessageParent(messageId))
                    } else {
                        pendingSourceMessage = msg
                        beginLoad(account, freshDoc, MessageObject(account, msg, false, false))
                    }
                }
            }
        }
    }

    fun cancelDownload(account: Int) {
        if (isPendingStart) {
            isPendingStart = false
        } else {
            SharedConfig.pendingAppUpdate?.document?.let {
                FileLoader.getInstance(account).cancelLoadFile(it)
            }
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading)
    }

    private fun refreshPendingAndStart(account: Int) {
        check {
            AndroidUtilities.runOnUIThread {
                if (!isPendingStart) return@runOnUIThread
                if ((SharedConfig.pendingAppUpdate?.id ?: 0) > 0) {
                    startDownload(account)
                } else {
                    stopPendingStart()
                }
            }
        }
    }

    private fun sourceMessageParent(messageId: Int) = "sent_${CHANNEL_ID}_${messageId}"

    private fun stopPendingStart() {
        isPendingStart = false
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading)
    }

    private fun beginLoad(account: Int, document: TLRPC.Document, parent: Any) {
        isPendingStart = false
        FileLoader.getInstance(account).loadFile(document, parent, FileLoader.PRIORITY_NORMAL, 1)
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading)
    }

    fun check(callback: ((CheckResult) -> Unit)?) {
        if (BuildConfig.INU_BUILD_TYPE == "debug") {
            callback?.invoke(CheckResult.UpToDate)
            return
        }
        check(currentVariant(), callback)
    }

    fun prepareVariantSwitch(callback: (VariantSwitchResult) -> Unit) {
        if (BuildConfig.INU_BUILD_TYPE == "debug") {
            callback(VariantSwitchResult.Unavailable)
            return
        }
        val account = UserConfig.selectedAccount
        if (!UserConfig.getInstance(account).isClientActivated) {
            callback(VariantSwitchResult.Error("Not logged in"))
            return
        }
        val now = System.currentTimeMillis()
        if (inflight && now - inflightSince < INFLIGHT_TIMEOUT_MS) {
            callback(VariantSwitchResult.InFlight)
            return
        }
        inflight = true
        inflightSince = now
        MessagesController.getInstance(account).userNameResolver.resolve(USERNAME) { id ->
            if (id == null || id == 0L || id == Long.MAX_VALUE) {
                finishVariantSwitch(callback, VariantSwitchResult.Error("resolve failed"))
                return@resolve
            }
            val req = TLRPC.TL_messages_search().apply {
                peer = MessagesController.getInstance(account).getInputPeer(id)
                q = "#release"
                filter = TLRPC.TL_inputMessagesFilterEmpty()
                limit = 10
            }
            ConnectionsManager.getInstance(account).sendRequest(req) { resp, err ->
                AndroidUtilities.runOnUIThread {
                    if (err != null || resp !is TLRPC.messages_Messages) {
                        finishVariantSwitch(callback, VariantSwitchResult.Error(err?.text ?: "no response"))
                        return@runOnUIThread
                    }
                    val targetVariant = if (BuildConfig.INU_PLUGINLESS) "full" else "pluginless"
                    val latest = resp.messages.firstOrNull { extractApkInfo(it, targetVariant) != null }
                    fun onLoaded(msg: TLRPC.Message?) {
                        val info = msg?.let { extractApkInfo(it, targetVariant) }
                        finishVariantSwitch(
                            callback,
                            if (msg == null || info == null || info.verCode < currentBuild().versionCode) VariantSwitchResult.Unavailable
                            else VariantSwitchResult.Ready(VariantSwitch(info.document, msg)),
                        )
                    }
                    if (latest?.rich_message?.part == true) loadRichMessage(account, latest.id, ::onLoaded)
                    else onLoaded(latest)
                }
            }
        }
    }

    fun startVariantSwitchDownload(account: Int, variantSwitch: VariantSwitch) {
        FileLoader.getInstance(account).loadFile(
            variantSwitch.document,
            MessageObject(account, variantSwitch.sourceMessage, false, false),
            FileLoader.PRIORITY_NORMAL,
            1,
        )
    }

    fun cancelVariantSwitchDownload(account: Int, variantSwitch: VariantSwitch) {
        FileLoader.getInstance(account).cancelLoadFile(variantSwitch.document)
    }

    private fun check(variant: String, callback: ((CheckResult) -> Unit)?) {
        val account = UserConfig.selectedAccount
        if (!UserConfig.getInstance(account).isClientActivated) {
            callback?.invoke(CheckResult.Error("Not logged in"))
            return
        }
        val now = System.currentTimeMillis()
        if (inflight && now - inflightSince < INFLIGHT_TIMEOUT_MS) {
            callback?.invoke(CheckResult.InFlight)
            return
        }
        inflight = true
        inflightSince = now
        MessagesController.getInstance(account).userNameResolver.resolve(USERNAME) { id ->
            if (id == null || id == 0L || id == Long.MAX_VALUE) {
                finish(callback, CheckResult.Error("resolve failed"))
                return@resolve
            }
            performSearch(account, id, variant, callback)
        }
    }

    private fun performSearch(
        account: Int,
        peerId: Long,
        variant: String,
        callback: ((CheckResult) -> Unit)?,
    ) {
        val mc = MessagesController.getInstance(account)
        val req = TLRPC.TL_messages_search().apply {
            peer = mc.getInputPeer(peerId)
            q = "#release"
            filter = TLRPC.TL_inputMessagesFilterEmpty()
            limit = 10
        }
        ConnectionsManager.getInstance(account).sendRequest(req) { resp, err ->
            AndroidUtilities.runOnUIThread {
                if (err != null || resp !is TLRPC.messages_Messages) {
                    finish(callback, CheckResult.Error(err?.text ?: "no response"))
                    return@runOnUIThread
                }
                fun processMessages(messages: List<TLRPC.Message>) {
                    val latest = messages.firstOrNull { extractApkInfo(it, variant) != null }
                    fun onLoaded(msg: TLRPC.Message?) {
                        val info = msg?.let { extractApkInfo(it, variant) }
                        val current = currentBuild()
                        if (info == null || !isNewer(info, current)) {
                            clearPending()
                            finish(callback, CheckResult.UpToDate)
                            return
                        }
                        finish(callback, CheckResult.Updated(applyUpdate(msg, info, current, variant)))
                    }
                    if (latest?.rich_message?.part == true) loadRichMessage(account, latest.id, ::onLoaded)
                    else onLoaded(latest)
                }

                val releaseMessages = resp.messages.filter { it.rich_message != null }
                if (releaseMessages.isNotEmpty()) {
                    processMessages(releaseMessages)
                } else {
                    val docReq = TLRPC.TL_messages_search().apply {
                        peer = mc.getInputPeer(peerId)
                        q = ""
                        filter = TLRPC.TL_inputMessagesFilterDocument()
                        limit = 20
                    }
                    ConnectionsManager.getInstance(account).sendRequest(docReq) { docResp, docErr ->
                        AndroidUtilities.runOnUIThread {
                            if (docErr != null || docResp !is TLRPC.messages_Messages) {
                                clearPending()
                                finish(callback, CheckResult.UpToDate)
                                return@runOnUIThread
                            }
                            processMessages(docResp.messages)
                        }
                    }
                }
            }
        }
    }

    // search results may hold a truncated rich message without all of its documents
    private fun loadRichMessage(account: Int, messageId: Int, callback: (TLRPC.Message?) -> Unit) {
        val req = TL_iv.getRichMessage().apply {
            peer = MessagesController.getInstance(account).getInputPeer(-CHANNEL_ID)
            id = messageId
        }
        ConnectionsManager.getInstance(account).sendRequest(req) { resp, _ ->
            AndroidUtilities.runOnUIThread {
                callback((resp as? TLRPC.messages_Messages)?.messages?.firstOrNull { it.id == messageId })
            }
        }
    }

    fun onNewMessage(msg: TLRPC.Message, account: Int) {
        if (!InuConfig.UPDATES_ENABLED.value) return
        if (BuildConfig.INU_BUILD_TYPE == "debug") return
        if (msg.peer_id?.channel_id != CHANNEL_ID) return
        fun onLoaded(full: TLRPC.Message?) {
            val variant = currentVariant()
            val info = full?.let { extractApkInfo(it, variant) } ?: return
            val current = currentBuild()
            if (!isNewer(info, current)) return
            applyUpdate(full, info, current, variant)
            revealPendingUpdate()
            InuConfig.UPDATE_LAST_CHECK_MS.value = System.currentTimeMillis()
        }
        val rich = msg.rich_message
        AndroidUtilities.runOnUIThread {
            if (rich?.part == true) loadRichMessage(account, msg.id, ::onLoaded)
            else onLoaded(msg)
        }
    }

    private fun applyUpdate(
        msg: TLRPC.Message,
        info: ApkInfo,
        current: CurrentBuild,
        variant: String,
    ): TLRPC.TL_help_appUpdate {
        val updateText: String
        val richMsg = msg.rich_message
        if (richMsg != null) {
            var skipping = false
            val blocks = richMsg.blocks.filterIsInstance<TL_iv.pageBlockDetails>().firstOrNull()?.blocks.orEmpty().filterTo(ArrayList()) {
                if (it is TL_iv.pageBlockHeading2) {
                    skipping = variant == "pluginless" && RichMessageLayout.getString(it.text).trim() == "Plugins"
                }
                !skipping
            }
            val changelog = TL_iv.RichMessage().apply {
                this.blocks = blocks
                photos = richMsg.photos
                documents = richMsg.documents
            }
            val data = SerializedData(changelog.objectSize)
            changelog.serializeToStream(data)
            updateText = Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)
            data.cleanup()
        } else {
            updateText = msg.message.orEmpty()
        }

        val updateObj = TLRPC.TL_help_appUpdate().apply {
            flags = flags or 2
            // stash the source channel message id and the serialized changelog in otherwise-unused fields
            id = msg.id
            version = info.verCode.toString()
            text = updateText
            document = info.document
            entities = msg.entities
        }

        SharedConfig.pendingAppUpdate = updateObj
        SharedConfig.pendingAppUpdateBuildVersion = current.versionCode
        SharedConfig.saveConfig()
        pendingBetaUpdate = BetaUpdate(info.appVerName, info.verCode, null)
        pendingSourceMessage = msg
        pendingSha256 = info.sha256
        return updateObj
    }

    private fun finish(callback: ((CheckResult) -> Unit)?, result: CheckResult) {
        inflight = false
        InuConfig.UPDATE_LAST_CHECK_MS.value = System.currentTimeMillis()
        callback?.invoke(result)
    }

    private fun finishVariantSwitch(
        callback: (VariantSwitchResult) -> Unit,
        result: VariantSwitchResult,
    ) {
        inflight = false
        callback(result)
    }

    @Suppress("DEPRECATION")
    private fun currentBuild(): CurrentBuild = CurrentBuild(packageInfo.versionCode)

    private fun extractApkInfo(msg: TLRPC.Message, variant: String): ApkInfo? {
        val (match, doc) = msg.rich_message?.documents.orEmpty().firstNotNullOfOrNull { doc ->
            APK_RE.matchEntire(FileLoader.getDocumentFileName(doc))
                ?.takeIf { it.groupValues[3] == variant }
                ?.let { it to doc }
        } ?: (msg.media as? TLRPC.TL_messageMediaDocument)?.document?.let { doc ->
            APK_RE.matchEntire(FileLoader.getDocumentFileName(doc))
                ?.takeIf { it.groupValues[3] == variant }
                ?.let { it to doc }
        } ?: return null
        val verName = match.groupValues[1]
        val verCode = match.groupValues[2].toIntOrNull() ?: return null
        val appVerName = verName.replace(SHORT_SHA_RE, "")
        val sha256 = Regex("""(?:SHA256|Hash)\s*(?:\($variant\)|-$variant)?:\s*[`<]*([a-fA-F0-9]{64})""", RegexOption.IGNORE_CASE)
            .find(msg.message.orEmpty())?.groups?.get(1)?.value?.trim()
            ?: SHA256_RE.find(msg.message.orEmpty())?.groups?.get(1)?.value?.trim()
        return ApkInfo(verCode, appVerName, doc, sha256)
    }

    private fun currentVariant(): String = if (BuildConfig.INU_PLUGINLESS) "pluginless" else "full"

    private fun isNewer(remote: ApkInfo, current: CurrentBuild): Boolean {
        return remote.verCode > current.versionCode
    }

    sealed class CheckResult {
        object InFlight : CheckResult()
        object UpToDate : CheckResult()
        data class Updated(val update: TLRPC.TL_help_appUpdate) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    sealed class VariantSwitchResult {
        object InFlight : VariantSwitchResult()
        object Unavailable : VariantSwitchResult()
        data class Ready(val variantSwitch: VariantSwitch) : VariantSwitchResult()
        data class Error(val message: String) : VariantSwitchResult()
    }

    class VariantSwitch internal constructor(
        val document: TLRPC.Document,
        internal val sourceMessage: TLRPC.Message,
    )

    private data class ApkInfo(
        val verCode: Int,
        val appVerName: String,
        val document: TLRPC.Document,
        val sha256: String? = null,
    )

    private data class CurrentBuild(
        val versionCode: Int,
    )
}
