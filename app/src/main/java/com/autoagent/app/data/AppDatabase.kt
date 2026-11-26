package com.autoagent.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
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
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "autoagent.db").build()

        val DEFAULT_RULES = listOf(
            KeywordRule(
                intent = RuleIntent.URGENT,
                keywords = "urgent,emergency,asap,right now,hospital,accident,דחוף",
                response = "I have marked this as urgent. The owner will see it right away.",
                priority = 50,
            ),
            KeywordRule(
                intent = RuleIntent.WHO_IS_THIS,
                keywords = "who is this,who am i speaking,who are you,whose phone,is this a robot,are you a bot,מי זה",
                response = "You have reached the owner's assistant. The owner cannot take the call right now.",
                priority = 40,
            ),
            KeywordRule(
                intent = RuleIntent.CALLBACK_TIME,
                keywords = "call back,call me back,callback,when,what time,available,reach him,reach her,reach them",
                response = "The owner will call you back as soon as possible. If there is a better time to reach you, please say it now.",
                priority = 30,
            ),
            KeywordRule(
                intent = RuleIntent.TAKE_MESSAGE,
                keywords = "message,tell him,tell her,tell them,let him know,let her know,let them know,pass on,remind",
                response = "Of course. Please say your message now and I will save it for the owner.",
                priority = 20,
            ),
            KeywordRule(
                intent = RuleIntent.SALES,
                keywords = "offer,promotion,insurance,survey,special deal,discount,credit card",
                response = "Thank you. The owner does not take sales calls. I will still note your number.",
                priority = 10,
            ),
        )
    }
}
