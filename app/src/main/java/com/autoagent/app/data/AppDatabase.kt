package com.autoagent.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AlarmEntity::class,
        ScheduledSend::class,
        KeywordRule::class,
        CallRecord::class,
        InboxEntry::class,
        Receipt::class,
        ActionLog::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarms(): AlarmDao
    abstract fun sends(): SendDao
    abstract fun rules(): RuleDao
    abstract fun calls(): CallRecordDao
    abstract fun inbox(): InboxDao
    abstract fun receipts(): ReceiptDao
    abstract fun logs(): LogDao

    companion object {
        /** v2: Hebrew replies + Hebrew keywords for the built-in call rules. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE keyword_rules ADD COLUMN responseHe TEXT NOT NULL DEFAULT ''")
                for (r in DEFAULT_RULES) {
                    db.execSQL(
                        "UPDATE keyword_rules SET keywords = ?, responseHe = ? WHERE intent = ?",
                        arrayOf<Any>(r.keywords, r.responseHe, r.intent),
                    )
                }
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "autoagent.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        val DEFAULT_RULES = listOf(
            KeywordRule(
                intent = RuleIntent.URGENT,
                keywords = "urgent,emergency,asap,right now,hospital,accident," +
                    "דחוף,דחופה,חירום,מקרה חירום,בהול,בהולה,בית חולים,תאונה,חשוב מאוד,מיד,פצוע",
                response = "I have marked this as urgent. The owner will see it right away.",
                responseHe = "סימנתי את זה כדחוף. זה יגיע לבעל המספר מיד.",
                priority = 50,
            ),
            KeywordRule(
                intent = RuleIntent.WHO_IS_THIS,
                keywords = "who is this,who am i speaking,who are you,whose phone,is this a robot,are you a bot," +
                    "מי זה,מי מדבר,מי מדברת,מי אתה,מי את,עם מי אני מדבר,עם מי אני מדברת,של מי הטלפון,של מי המספר,רובוט,בוט,מענה קולי,מכונה",
                response = "You have reached the owner's assistant. The owner cannot take the call right now.",
                responseHe = "הגעת לעוזר האישי של בעל המספר. כרגע אי אפשר לענות לשיחה.",
                priority = 40,
            ),
            KeywordRule(
                intent = RuleIntent.CALLBACK_TIME,
                keywords = "call back,call me back,callback,when,what time,available,reach him,reach her,reach them," +
                    "תחזור אליי,תחזרו אליי,יחזור אליי,שיחזור,שיחזרו,לחזור אליי,יחזרו,מתי,באיזו שעה,באיזה שעה,זמין,זמינה,להשיג,יתקשר,שיתקשר,שתתקשר,לדבר איתו,לדבר איתה",
                response = "The owner will call you back as soon as possible. If there is a better time to reach you, please say it now.",
                responseHe = "יחזרו אליך בהקדם האפשרי. אם יש שעה נוחה יותר, אפשר לומר אותה עכשיו.",
                priority = 30,
            ),
            KeywordRule(
                intent = RuleIntent.TAKE_MESSAGE,
                keywords = "message,tell him,tell her,tell them,let him know,let her know,let them know,pass on,remind," +
                    "הודעה,להשאיר הודעה,תגיד לו,תגידי לו,תגיד לה,תגידי לה,תמסור,תמסרי,למסור,תעביר,תעבירי,להעביר,תרשום,תרשמי,תזכיר לו,תזכירי לה,שידע,שתדע",
                response = "Of course. Please say your message now and I will save it for the owner.",
                responseHe = "בשמחה. אפשר לומר את ההודעה עכשיו, ואני אשמור אותה.",
                priority = 20,
            ),
            KeywordRule(
                intent = RuleIntent.SALES,
                keywords = "offer,promotion,insurance,survey,special deal,discount,credit card," +
                    "מבצע,הצעה,ביטוח,סקר,הנחה,כרטיס אשראי,הלוואה,פנסיה,משכנתא,שיווק,נציג מכירות,מבצע מיוחד",
                response = "Thank you. The owner does not take sales calls. I will still note your number.",
                responseHe = "תודה. לא מעוניינים בשיחות מכירה. המספר שלך נרשם.",
                priority = 10,
            ),
        )
    }
}
