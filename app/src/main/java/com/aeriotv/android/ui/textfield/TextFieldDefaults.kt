package com.aeriotv.android.ui.textfield

import android.content.res.Configuration
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * Default KeyboardOptions for every text field in the app. Samsung Keyboard's
 * predictive-text composition pathway breaks IME → TextField commits when
 * the field starts empty: typing produces no characters, but pasting works,
 * and once the field has any content typing starts behaving. Setting
 * `autoCorrectEnabled = false` sidesteps composition mode entirely and
 * routes every keystroke as a direct commit.
 *
 * Callers that want a tailored IME (Uri, password, number) pass the type
 * + a sensible default action. Hex and numeric inputs also opt out of the
 * IME's predictive bar via this same helper.
 *
 * TV: the floating leanback IME's Next action key hops focus straight to the
 * next TEXT FIELD with the keyboard intentionally left open (skipping
 * non-field rows in between), which a remote user experiences as "the next
 * field auto-opened the keyboard". Coerce Next to Done on TV so the action
 * key closes the panel after each field (Compose's default Done action hides
 * the IME); the user D-pads onward and the keyboard-on-OK gate
 * (TvKeyboardOnOkHost) decides when it reopens. Search and explicit Done
 * keep their semantics; phones keep Next.
 */
@Composable
fun aerioTextFieldKeyboardOptions(
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
): KeyboardOptions {
    val configuration = LocalConfiguration.current
    val isTv = (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    val resolvedImeAction =
        if (isTv && imeAction == ImeAction.Next) ImeAction.Done else imeAction
    return KeyboardOptions(
        keyboardType = keyboardType,
        imeAction = resolvedImeAction,
        capitalization = capitalization,
        autoCorrectEnabled = false,
    )
}
