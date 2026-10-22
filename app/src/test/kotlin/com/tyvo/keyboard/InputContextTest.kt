package com.tyvo.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.tyvo.keyboard.data.InputContext
import org.junit.Assert.assertEquals
import org.junit.Test

class InputContextTest {

    private fun ctx(inputType: Int, imeOptions: Int = 0) =
        InputContext.of(inputType, imeOptions)

    @Test
    fun `plain text fields are text`() {
        assertEquals(InputContext.TEXT, ctx(InputType.TYPE_CLASS_TEXT))
        assertEquals(InputContext.TEXT, ctx(0))
    }

    @Test
    fun `number phone and datetime classes are numeric`() {
        assertEquals(InputContext.NUMERIC, ctx(InputType.TYPE_CLASS_NUMBER))
        assertEquals(InputContext.NUMERIC, ctx(InputType.TYPE_CLASS_PHONE))
        assertEquals(InputContext.NUMERIC, ctx(InputType.TYPE_CLASS_DATETIME))
        // A signed decimal and a PIN are number-class fields too.
        assertEquals(
            InputContext.NUMERIC,
            ctx(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        )
        assertEquals(
            InputContext.NUMERIC,
            ctx(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        )
    }

    @Test
    fun `a numeric class beats the search action`() {
        // A phone field with a search action is still a phone field.
        assertEquals(
            InputContext.NUMERIC,
            ctx(InputType.TYPE_CLASS_PHONE, EditorInfo.IME_ACTION_SEARCH)
        )
    }

    @Test
    fun `password variations are password`() {
        val cls = InputType.TYPE_CLASS_TEXT
        assertEquals(InputContext.PASSWORD, ctx(cls or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertEquals(
            InputContext.PASSWORD,
            ctx(cls or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        )
        assertEquals(InputContext.PASSWORD, ctx(cls or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
    }

    @Test
    fun `a password variation beats the search action`() {
        assertEquals(
            InputContext.PASSWORD,
            ctx(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                EditorInfo.IME_ACTION_SEARCH
            )
        )
    }

    @Test
    fun `the uri variation is uri`() {
        assertEquals(
            InputContext.URI,
            ctx(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        )
    }

    @Test
    fun `the search action is search`() {
        assertEquals(
            InputContext.SEARCH,
            ctx(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH)
        )
    }

    @Test
    fun `other editor actions are still text`() {
        for (action in listOf(
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NONE,
        )) {
            assertEquals("action $action", InputContext.TEXT, ctx(InputType.TYPE_CLASS_TEXT, action))
        }
    }

    @Test
    fun `an email field is text`() {
        // Dictating an address works fine; only URI-typed fields opt out.
        assertEquals(
            InputContext.TEXT,
            ctx(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        )
    }
}
