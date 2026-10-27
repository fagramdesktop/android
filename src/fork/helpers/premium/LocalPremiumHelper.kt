package xie.fa.gram.helpers.premium

import org.telegram.tgnet.TLRPC
import xie.fa.gram.InuConfig

object LocalPremiumHelper {

    @JvmStatic
    fun canShowPremiumFor(user: TLRPC.User?): Boolean =
        InuConfig.LOCAL_PREMIUM.value && user != null && user.self
}