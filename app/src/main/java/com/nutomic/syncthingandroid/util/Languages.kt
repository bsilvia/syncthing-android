package com.nutomic.syncthingandroid.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import javax.inject.Inject

/**
 * Based on https://gitlab.com/fdroid/fdroidclient/blob/master/app/src/main/java/org/fdroid/fdroid/Languages.java
 */
class Languages(context: Context) {
    @JvmField
    @Inject
    var mPreferences: SharedPreferences? = null

    /**
     * Handles setting the language if it is different than the current language,
     * or different than the current system-wide locale.  The preference is cleared
     * if the language matches the system-wide locale or "System Default" is chosen.
     */
    fun setLanguage(context: Context) {
//        val language = mPreferences!!.getString(PREFERENCE_LANGUAGE, null)
//        val locale: Locale?
//        if (TextUtils.equals(language, DEFAULT_LOCALE.getLanguage())) {
//            mPreferences!!.edit().remove(PREFERENCE_LANGUAGE).apply()
//            locale = DEFAULT_LOCALE
//        } else if (language == null || language == USE_SYSTEM_DEFAULT) {
//            mPreferences!!.edit().remove(PREFERENCE_LANGUAGE).apply()
//            locale = DEFAULT_LOCALE
//        } else {
//            /* handle locales with the country in it, i.e. zh_CN, zh_TW, etc */
//            val localeSplit: Array<String?> =
//                language.split("_".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
//            if (localeSplit.size > 1) {
//                locale = Locale(localeSplit[0], localeSplit[1])
//            } else {
//                locale = Locale(language)
//            }
//        }
//        Locale.setDefault(locale)
//
//        val resources = context.getResources()
//        val config = resources.getConfiguration()
//        config.setLocale(locale)
//        resources.updateConfiguration(config, resources.getDisplayMetrics())
    }

    /**
     * Force reload the [to make language changes take effect.][Activity]
     *
     * @param activity the `Activity` to force reload
     */
    @SuppressLint("ApplySharedPref")
    fun forceChangeLanguage(activity: Activity, newLanguage: String?) {
//        mPreferences!!.edit().putString(PREFERENCE_LANGUAGE, newLanguage).commit()
//        setLanguage(activity)
//        val intent = activity.getIntent()
//        if (intent == null) { // when launched as LAUNCHER
//            return
//        }
//        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
//        activity.finish()
//        activity.overridePendingTransition(0, 0)
//        activity.startActivity(intent)
//        activity.overridePendingTransition(0, 0)
    }

    val allNames: Array<String?>
        /**
         * @return an array of the names of all the supported languages, sorted to
         * match what is returned by [Languages.supportedLocales].
         */
        get() = arrayOf() //mAvailableLanguages.values.toTypedArray<String?>()

    val supportedLocales: Array<String?>
        /**
         * @return sorted list of supported locales.
         */
        get() {
//            val keys: MutableSet<String?> =
//                mAvailableLanguages.keys
//            return keys.toTypedArray<String?>()
            return arrayOf()
        }

    init {
//        (context.getApplicationContext() as SyncthingApp).component()!!.inject(this)
//        val tmpMap: MutableMap<String?, String?> = TreeMap<String?, String?>()
//        val locales = Arrays.asList<Locale?>(*LOCALES_TO_TEST)
//        // Capitalize language names
//        Collections.sort<Locale?>(
//            locales,
//            Comparator { l1: Locale?, l2: Locale? ->
//                l1!!.getDisplayLanguage().compareTo(l2!!.getDisplayLanguage())
//            })
//        for (locale in locales) {
//            var displayLanguage = locale.getDisplayLanguage(locale)
//            displayLanguage =
//                displayLanguage.substring(0, 1).uppercase(locale) + displayLanguage.substring(1)
//            tmpMap.put(locale.getLanguage(), displayLanguage)
//        }
//
//        // remove the current system language from the menu
//        tmpMap.remove(Locale.getDefault().getLanguage())
//
//        /* SYSTEM_DEFAULT is a fake one for displaying in a chooser menu. */
//        tmpMap.put(USE_SYSTEM_DEFAULT, context.getString(R.string.pref_language_default))
//        mAvailableLanguages = Collections.unmodifiableMap<String?, String?>(tmpMap)
    }

    companion object {
        const val USE_SYSTEM_DEFAULT: String = ""

//        private val DEFAULT_LOCALE: Locale
        const val PREFERENCE_LANGUAGE: String = "pref_current_language"

//        private val mAvailableLanguages: MutableMap<String?, String?>

        init {
//            DEFAULT_LOCALE = Locale.getDefault()
        }

        private val LOCALES_TO_TEST = arrayOf<Locale?>(
            Locale.ENGLISH,
            Locale.FRENCH,
            Locale.GERMAN,
            Locale.ITALIAN,
            Locale.JAPANESE,
            Locale.KOREAN,
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE,
//            Locale("af"),
//            Locale("ar"),
//            Locale("be"),
//            Locale("bg"),
//            Locale("ca"),
//            Locale("cs"),
//            Locale("da"),
//            Locale("el"),
//            Locale("es"),
//            Locale("eo"),
//            Locale("et"),
//            Locale("eu"),
//            Locale("fa"),
//            Locale("fi"),
//            Locale("he"),
//            Locale("hi"),
//            Locale("hu"),
//            Locale("hy"),
//            Locale("id"),
//            Locale("is"),
//            Locale("it"),
//            Locale("ml"),
//            Locale("my"),
//            Locale("nb"),
//            Locale("nl"),
//            Locale("pl"),
//            Locale("pt"),
//            Locale("ro"),
//            Locale("ru"),
//            Locale("sc"),
//            Locale("sk"),
//            Locale("sn"),
//            Locale("sr"),
//            Locale("sv"),
//            Locale("th"),
//            Locale("tr"),
//            Locale("uk"),
//            Locale("vi"),
        )
    }
}
