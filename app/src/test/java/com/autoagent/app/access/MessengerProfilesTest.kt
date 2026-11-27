package com.autoagent.app.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessengerProfilesTest {
    @Test
    fun israeliNumbersBecomeInternational() {
        assertEquals("972501234567", MessengerProfiles.internationalDigits("050-123-4567"))
        assertEquals("972501234567", MessengerProfiles.internationalDigits("+972 50 123 4567"))
        assertEquals("972501234567", MessengerProfiles.internationalDigits("00972501234567"))
        assertEquals("12025550100", MessengerProfiles.internationalDigits("+1 (202) 555-0100"))
    }

    @Test
    fun whatsappLinkEncodesHebrewText() {
        val link = MessengerProfiles.forPackage("com.whatsapp").deepLink!!("0501234567", "שלום עולם & כל השאר")
        assertEquals(
            "https://api.whatsapp.com/send?phone=972501234567&text=%D7%A9%D7%9C%D7%95%D7%9D%20%D7%A2%D7%95%D7%9C%D7%9D%20%26%20%D7%9B%D7%9C%20%D7%94%D7%A9%D7%90%D7%A8",
            link,
        )
        // A group/contact name has no deep link: the app is opened with shared text and the name is picked on screen.
        assertNull(MessengerProfiles.forPackage("com.whatsapp").deepLink!!("משפחה", "היי"))
    }

    @Test
    fun telegramAndUnknownApps() {
        assertEquals("tg://resolve?domain=dana&text=%D7%94%D7%99%D7%99", MessengerProfiles.forPackage("org.telegram.messenger").deepLink!!("@dana", "היי"))
        val unknown = MessengerProfiles.forPackage("com.example.chat")
        assertNull(unknown.deepLink)
        assertEquals("com.example.chat:id/send", MessengerProfiles.sendViewIds("com.example.chat").first())
    }

    @Test
    fun sendButtonRecognition() {
        assertEquals("com.whatsapp:id/send", MessengerProfiles.sendViewIds("com.whatsapp").first())
        assertEquals(MessengerProfiles.sendViewIds("com.whatsapp").size, MessengerProfiles.sendViewIds("com.whatsapp").toSet().size)
        assertTrue(MessengerProfiles.isSendLabel(" שלח "))
        assertTrue(MessengerProfiles.isSendLabel("Send"))
        assertFalse(MessengerProfiles.isSendLabel("שלחתי"))
        assertFalse(MessengerProfiles.isSendLabel(null))
        assertTrue(MessengerProfiles.isPhone("+972-50-1234567"))
        assertFalse(MessengerProfiles.isPhone("דנה"))
    }
}
