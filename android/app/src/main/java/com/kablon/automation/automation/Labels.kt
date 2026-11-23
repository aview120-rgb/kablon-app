package com.kablon.automation.automation

/** תוויות כפתורים בעברית ובאנגלית שהאוטומציה מחפשת על המסך. */
object Labels {
    /** כפתורי אישור/המשך "פשוטים" שמותר לנסות ללחוץ עליהם אוטומטית. */
    val CONFIRM = listOf(
        "OK", "אישור", "אוקיי", "Continue", "המשך", "Allow", "אפשר", "התר",
        "While using the app", "Allow while using the app", "בזמן השימוש באפליקציה",
        "Accept", "Agree", "I agree", "אני מסכים", "מסכים", "מאשר", "Got it", "הבנתי",
        "Not now", "לא עכשיו", "Skip", "דלג", "Close", "סגור", "Dismiss", "Maybe later", "אולי אחר כך",
    )

    /** כפתורים שאסור ללחוץ עליהם אוטומטית בניסיון אישור כללי. */
    val NEVER_AUTO = listOf(
        "Delete", "מחק", "Uninstall", "הסר התקנה", "Pay", "שלם", "Buy", "קנה", "Subscribe", "הירשם",
        "Log out", "התנתק", "Remove", "Report", "דווח", "Block", "חסום",
    )

    val SEND = listOf("Send", "שלח", "שליחה", "Send message", "Send prompt", "Submit", "שלח הודעה")

    // ---- TikTok ----
    val TT_CREATE = listOf("Create", "צור", "יצירה", "Post a video", "צור סרטון")
    val TT_UPLOAD = listOf("Upload", "העלאה", "העלה", "Gallery", "גלריה", "Album", "אלבום")
    val TT_NEXT = listOf("Next", "הבא", "המשך")
    val TT_ADD_SOUND = listOf("Add sound", "הוספת צליל", "הוסף צליל", "Sounds", "צלילים", "Add music", "הוסף מוזיקה", "Sound", "צליל")
    val TT_SEARCH = listOf("Search", "חיפוש", "חפש")
    val TT_USE_SOUND = listOf("Use this sound", "השתמש בצליל הזה", "השתמש בצליל", "Use", "השתמש", "Add", "הוסף", "Done", "סיום")
    val TT_DESCRIPTION = listOf(
        "Describe your post", "Add description", "Add a catchy title", "Write a caption",
        "תאר את הפוסט", "הוסף תיאור", "הוספת תיאור", "תיאור", "כתוב כיתוב",
    )
    val TT_POST = listOf("Post", "פרסם", "פרסום", "Publish")
}
