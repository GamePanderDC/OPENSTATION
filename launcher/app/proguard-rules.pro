# Keep debuggable line numbers & compact source file name for crash diagnostics
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Android components & custom Views instantiated from XML/framework
-keep class com.openstation.launcher.MainActivity { *; }
-keep class com.openstation.launcher.YandexMusicActivity { *; }
-keep class com.openstation.launcher.SetupWizardActivity { *; }
-keep class com.openstation.launcher.RemoteSetupActivity { *; }
-keep class com.openstation.launcher.DjDeckActivity { *; }
-keep class com.openstation.launcher.VoiceAssistActivity { *; }
-keep class com.openstation.launcher.StationAudioService { *; }
-keep class com.openstation.launcher.BootReceiver { *; }
-keep class com.openstation.launcher.market.MarketInstallReceiver { *; }
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
