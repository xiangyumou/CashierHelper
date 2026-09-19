package pro.xiangyu.cashierhelper

import android.app.Application
import pro.xiangyu.cashierhelper.feedback.FeedbackNotifier

class CashierHelperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FeedbackNotifier.createNotificationChannel(this)
    }
}

