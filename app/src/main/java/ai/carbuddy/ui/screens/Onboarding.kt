package ai.carbuddy.ui.screens

import ai.carbuddy.ui.components.Bubble
import ai.carbuddy.ui.components.BuddyAvatar
import ai.carbuddy.ui.components.CbButton
import ai.carbuddy.ui.components.ButtonKind
import ai.carbuddy.ui.t
import ai.carbuddy.ui.theme.Cb
import ai.carbuddy.ui.theme.Dimens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** What the user chose in F0; required consents are implied by finishing. */
data class OnboardingResult(val buddyName: String, val recapOn: Boolean, val ambientOn: Boolean, val syncOn: Boolean)

/**
 * F0 첫 실행 (UX_SPEC F0·8장): 소개 → 동의(필수/선택) → 시스템 권한 → 버디 이름 → 완료.
 * Phone pairing (step 4) needs the companion server (PT) and is skipped for now.
 * [requestPermissions] shows the system dialogs for location / speed after the consent step.
 */
@Composable
fun OnboardingScreen(requestPermissions: () -> Unit, onDone: (OnboardingResult) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var recap by rememberSaveable { mutableStateOf(true) }
    var ambient by rememberSaveable { mutableStateOf(true) }
    var sync by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val presets = t("onboard_name_presets").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val chosenName = name.trim().ifEmpty { presets.firstOrNull() ?: "버디" }.take(MAX_NAME)

    val bubble = when (step) {
        0 -> t("onboard_intro")
        1 -> t("onboard_consent_title")
        2 -> t("onboard_name_title")
        else -> t("onboard_done")
    }
    val content: @Composable ColumnScope.() -> Unit = {
        when (step) {
            0 -> {
                Text(t("onboard_onceOnly"), style = Cb.type.body, color = Cb.colors.textSecondary)
                Spacer(Modifier.height(28.dp))
                CbButton(t("onboard_start"), { step = 1 }, Modifier.fillMaxWidth())
            }
            1 -> {
                Label(t("onboard_required"))
                ConsentRow(t("onboard_location"), t("settings_privacy"), checked = true, locked = true)
                ConsentRow(t("onboard_signals"), t("onboard_signals_desc"), checked = true, locked = true)
                Spacer(Modifier.height(12.dp))
                Label(t("onboard_optional"))
                ConsentRow(t("settings_recap"), t("onboard_recap_desc"), recap) { recap = it }
                ConsentRow(t("settings_ambient"), t("onboard_ambient_desc"), ambient) { ambient = it }
                ConsentRow(t("onboard_sync"), t("onboard_sync_desc"), sync) { sync = it }
                Spacer(Modifier.height(16.dp))
                Text(t("onboard_permission_hint"), style = Cb.type.caption, color = Cb.colors.textSecondary)
                Spacer(Modifier.height(12.dp))
                CbButton(t("onboard_agree"), { requestPermissions(); step = 2 }, Modifier.fillMaxWidth())
            }
            2 -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    presets.forEach { p ->
                        val on = chosenName == p
                        // full-size buttons: touch targets ≥ 64dp (SPEC 디자인 가이드)
                        CbButton(p, { name = p }, kind = if (on) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = if (name in presets) "" else name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    label = { Text(t("onboard_name_custom"), style = Cb.type.label) },
                    singleLine = true,
                    textStyle = Cb.type.body,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
                CbButton(t("onboard_next"), { name = chosenName; step = 3 }, Modifier.fillMaxWidth())
            }
            else -> {
                CbButton(t("onboard_finish"), { onDone(OnboardingResult(chosenName, recap, ambient, sync)) }, Modifier.fillMaxWidth())
            }
        }
    }

    val edge = Dimens.edge(Cb.landscape)
    if (Cb.landscape) {
        Row(Modifier.fillMaxSize().padding(edge), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(0.42f), horizontalAlignment = Alignment.CenterHorizontally) {
                BuddyAvatar(160.dp)
                Spacer(Modifier.height(24.dp))
                Bubble(bubble)
            }
            Column(Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, content = content)
        }
    } else {
        Column(
            Modifier.fillMaxSize().padding(edge).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            BuddyAvatar(140.dp)
            Spacer(Modifier.height(24.dp))
            Bubble(bubble)
            Spacer(Modifier.height(28.dp))
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = Cb.type.label.copy(fontWeight = FontWeight.Bold), color = Cb.colors.amber, modifier = Modifier.padding(vertical = 6.dp))

@Composable
private fun ConsentRow(title: String, desc: String, checked: Boolean, locked: Boolean = false, onChange: (Boolean) -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = Cb.colors.textPrimary)
            Text(desc, style = Cb.type.caption, color = Cb.colors.textSecondary, textAlign = TextAlign.Start)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked, onChange, enabled = !locked,
            // required items stay visibly ON while locked
            colors = SwitchDefaults.colors(checkedTrackColor = Cb.colors.teal, disabledCheckedTrackColor = Cb.colors.teal.copy(alpha = 0.6f), disabledCheckedThumbColor = Cb.colors.onAccent),
        )
    }
}

private const val MAX_NAME = 8
