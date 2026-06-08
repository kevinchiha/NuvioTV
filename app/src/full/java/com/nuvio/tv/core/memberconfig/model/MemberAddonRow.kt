package com.nuvio.tv.core.memberconfig.model

import androidx.annotation.Keep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of the Supabase `member_addon` table — the remote, per-member Stremio-addon config
 * (KevBox TV, full flavor only). The app only ever READS this table; the operator edits it from
 * the Supabase dashboard. See MEMBER-CONFIG-PLAN.md.
 *
 * All columns are modelled (with defaults / nullables) following the [com.nuvio.tv.data.remote.supabase.SupabaseAddon]
 * convention so a plain `select()` (which returns every column) decodes cleanly and a partially-
 * populated row never fails the whole list decode.
 *
 * @Keep + a dedicated R8 keep block in proguard-rules.pro are required: a minified RELEASE build
 * would otherwise rename/strip the fields + generated `$$serializer`, breaking deserialization.
 */
@Keep
@Serializable
data class MemberAddonRow(
    val id: Long? = null,
    @SerialName("user_id") val userId: String? = null,
    val url: String,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null
)
