package com.jamal2367.styx.di

import android.app.Application
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ShortcutManager
import android.content.res.AssetManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager

import com.jamal2367.styx.device.BuildInfo
import com.jamal2367.styx.device.BuildType
import com.jamal2367.styx.html.ListPageReader
import com.jamal2367.styx.html.bookmark.BookmarkPageReader
import com.jamal2367.styx.html.homepage.HomePageReader
import com.jamal2367.styx.js.*
import com.jamal2367.styx.log.AndroidLogger
import com.jamal2367.styx.log.Logger
import com.jamal2367.styx.log.NoOpLogger
import com.jamal2367.styx.search.suggestions.RequestFactory
import com.jamal2367.styx.utils.FileUtils
import dagger.Module
import dagger.Provides
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import net.i2p.android.ui.I2PAndroidHelper
import okhttp3.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
class AppModule {

    @Provides
    @MainHandler
    fun provideMainHandler() = Handler(Looper.getMainLooper())

    @Provides
    fun provideContext(application: Application): Context = application.applicationContext

    @Provides
    @UserPrefs
    // Access default shared preferences to make sure preferences framework binding is working from XML
    fun provideUserPreferences(application: Application): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(application.applicationContext)

    @Provides
    @DevPrefs
    fun provideDebugPreferences(application: Application): SharedPreferences = application.getSharedPreferences("developer_settings", 0)

    @Provides
    @AdBlockPrefs
    fun provideAdBlockPreferences(application: Application): SharedPreferences = application.getSharedPreferences("ad_block_settings", 0)

    @Provides
    fun providesAssetManager(application: Application): AssetManager = application.assets

    @Provides
    fun providesClipboardManager(application: Application) = application.getSystemService<ClipboardManager>()!!

    @Provides
    fun providesInputMethodManager(application: Application) = application.getSystemService<InputMethodManager>()!!

    @Provides
    fun providesDownloadManager(application: Application) = application.getSystemService<DownloadManager>()!!

    @Provides
    fun providesConnectivityManager(application: Application) = application.getSystemService<ConnectivityManager>()!!

    @Provides
    fun providesNotificationManager(application: Application) = application.getSystemService<NotificationManager>()!!

    @Provides
    fun providesWindowManager(application: Application) = application.getSystemService<WindowManager>()!!

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    @Provides
    fun providesShortcutManager(application: Application) = application.getSystemService<ShortcutManager>()!!

    @Provides
    @DatabaseScheduler
    @Singleton
    fun providesIoThread(): Scheduler = Schedulers.from(Executors.newSingleThreadExecutor())

    @Provides
    @DiskScheduler
    @Singleton
    fun providesDiskThread(): Scheduler = Schedulers.from(Executors.newSingleThreadExecutor())

    @Provides
    @NetworkScheduler
    @Singleton
    fun providesNetworkThread(): Scheduler = Schedulers.from(ThreadPoolExecutor(0, 4, 60, TimeUnit.SECONDS, LinkedBlockingDeque()))

    @Provides
    @MainScheduler
    @Singleton
    fun providesMainThread(): Scheduler = AndroidSchedulers.mainThread()

    @Singleton
    @Provides
    fun providesSuggestionsCacheControl(): CacheControl = CacheControl.Builder().maxStale(1, TimeUnit.DAYS).build()

    @Singleton
    @Provides
    fun providesSuggestionsRequestFactory(cacheControl: CacheControl): RequestFactory = object : RequestFactory {
        override fun createSuggestionsRequest(httpUrl: HttpUrl, encoding: String): Request {
            return Request.Builder().url(httpUrl)
                .addHeader("Accept-Charset", encoding)
                .cacheControl(cacheControl)
                .build()
        }
    }

    private fun createInterceptorWithMaxCacheAge(maxCacheAgeSeconds: Long) = Interceptor { chain ->
        chain.proceed(chain.request()).newBuilder()
            .header("cache-control", "max-age=$maxCacheAgeSeconds, max-stale=$maxCacheAgeSeconds")
            .build()
    }

    @Singleton
    @Provides
    @SuggestionsClient
    fun providesSuggestionsHttpClient(application: Application): Single<OkHttpClient> = Single.fromCallable {
        val intervalDay = TimeUnit.DAYS.toSeconds(1)
        val suggestionsCache = File(application.cacheDir, "suggestion_responses")

        return@fromCallable OkHttpClient.Builder()
            .cache(Cache(suggestionsCache, FileUtils.megabytesToBytes(1)))
            .addNetworkInterceptor(createInterceptorWithMaxCacheAge(intervalDay))
            .build()
    }.cache()

    @Singleton
    @Provides
    @HostsClient
    fun providesHostsHttpClient(application: Application): Single<OkHttpClient> = Single.fromCallable {
        val intervalYear = TimeUnit.DAYS.toSeconds(365)
        val suggestionsCache = File(application.cacheDir, "hosts_cache")

        return@fromCallable OkHttpClient.Builder()
            .cache(Cache(suggestionsCache, FileUtils.megabytesToBytes(5)))
            .addNetworkInterceptor(createInterceptorWithMaxCacheAge(intervalYear))
            .build()
    }.cache()

    @Provides
    @Singleton
    fun provideLogger(buildInfo: BuildInfo): Logger = if (buildInfo.buildType == BuildType.DEBUG) {
        AndroidLogger()
    } else {
        NoOpLogger()
    }

    @Provides
    @Singleton
    fun provideI2PAndroidHelper(application: Application): I2PAndroidHelper = I2PAndroidHelper(application)

    @Provides
    fun providesListPageReader(application: Application): ListPageReader = object : ListPageReader {
        override fun provideHtml() = application.assets.open("html/list.html").bufferedReader().use { it.readText() }
    }

    @Provides
    fun providesHomePageReader(application: Application): HomePageReader = object : HomePageReader {
        override fun provideHtml() = application.assets.open("html/homepage.html").bufferedReader().use { it.readText() }
    }

    @Provides
    fun providesBookmarkPageReader(application: Application): BookmarkPageReader = object : BookmarkPageReader {
        override fun provideHtml() = application.assets.open("html/bookmarks.html").bufferedReader().use { it.readText() }
    }

    @Provides
    fun providesTextReflow(application: Application): TextReflow = object : TextReflow {
        override fun provideJs() = application.assets.open("js/TextReflow.js").bufferedReader().use { it.readText() }
    }

    @Provides
    fun providesThemeColor(application: Application): ThemeColor = object : ThemeColor {
        override fun provideJs() = application.assets.open("js/ThemeColor.js").bufferedReader().use { it.readText() }
    }

    @Provides
    fun providesInvertPage(application: Application): InvertPage = object : InvertPage {
        override fun provideJs() = application.assets.open("js/InvertPage.js").bufferedReader().use { it.readText() }
    }

    @Provides
    fun providesSetMetaViewport(application: Application): SetMetaViewport = object : SetMetaViewport {
        override fun provideJs() = application.assets.open("js/SetMetaViewport.js").bufferedReader().use { it.readText() }
    }

}

@Qualifier
@Retention(AnnotationRetention.SOURCE)
annotation class SuggestionsClient

@Qualifier
@Retention(AnnotationRetention.SOURCE)
annotation class HostsClient

@Qualifier
@Retention(AnnotationRetention.SOURCE)
annotation class MainHandler

@Qualifier
@Retention(AnnotationRetention.SOURCE)
annotation class UserPrefs

@Qualifier
@Retention(AnnotationRetention.SOURCE)
annotation class AdBlockPrefs

@Qualifier
@Retention(AnnotationRetention.SOURCE)
annotation class DevPrefs

@Qualifier
@Retention(AnnotationRetention.SOURCE)
annotation class MainScheduler

@Qualifier
@Retention(AnnotationRetention.SOURCE)
annotation class DiskScheduler

@Qualifier
@Retention(AnnotationRetention.SOURCE)
annotation class NetworkScheduler

@Qualifier
@Retention(AnnotationRetention.SOURCE)
annotation class DatabaseScheduler
