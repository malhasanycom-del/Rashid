# راشد — مشروع Android مستقل

مشروع جديد مستقل باستخدام Kotlin وJetpack Compose وMedia3، مخصص للهاتف والتلفاز مع دعم أولي للتنقل بالريموت.

## الحالة الحالية
هذه نسخة تأسيسية وليست تطبيق IPTV مكتملًا بعد. تحتوي على إعدادات Gradle، واجهة عربية أولية، نموذج قناة، محلل M3U أولي، وسير عمل GitHub Actions لبناء APK. استيراد المصادر فعليًا وتشغيل البث وXtream Codes وStalker Portal وEPG ما زالت بحاجة إلى تنفيذ واختبار.

## المتطلبات
JDK 17، Android SDK Platform 35، وAndroid Gradle Plugin 8.7.3.

## البناء
`gradle assembleDebug`

المخرج: `app/build/outputs/apk/debug/app-debug.apk`
