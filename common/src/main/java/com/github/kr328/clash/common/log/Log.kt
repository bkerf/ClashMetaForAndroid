package com.github.kr328.clash.common.log

object Log {
    private const val TAG = "ClashMetaForAndroid"

    fun tagged(tag: String): TaggedLogger = TaggedLogger(tag)

    fun i(message: String, throwable: Throwable? = null) =
        android.util.Log.i(TAG, message, throwable)

    fun w(message: String, throwable: Throwable? = null) =
        android.util.Log.w(TAG, message, throwable)

    fun e(message: String, throwable: Throwable? = null) =
        android.util.Log.e(TAG, message, throwable)

    fun d(message: String, throwable: Throwable? = null) =
        android.util.Log.d(TAG, message, throwable)

    fun v(message: String, throwable: Throwable? = null) =
        android.util.Log.v(TAG, message, throwable)

    fun f(message: String, throwable: Throwable) =
        android.util.Log.wtf(TAG, message, throwable)

    class TaggedLogger(private val tag: String) {
        fun i(message: String, throwable: Throwable? = null) =
            android.util.Log.i(tag, message, throwable)

        fun w(message: String, throwable: Throwable? = null) =
            android.util.Log.w(tag, message, throwable)

        fun e(message: String, throwable: Throwable? = null) =
            android.util.Log.e(tag, message, throwable)

        fun d(message: String, throwable: Throwable? = null) =
            android.util.Log.d(tag, message, throwable)

        fun v(message: String, throwable: Throwable? = null) =
            android.util.Log.v(tag, message, throwable)

        fun f(message: String, throwable: Throwable) =
            android.util.Log.wtf(tag, message, throwable)
    }
}
