package ru.usedesk.common_sdk.api

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.google.android.gms.security.ProviderInstaller
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

internal class UsedeskOkHttpClientFactory @Inject constructor(
    private val appContext: Context
) : IUsedeskOkHttpClientFactory {
    override fun createInstance(): OkHttpClient = OkHttpClient.Builder().apply {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP) {
            try {
                ProviderInstaller.installIfNeeded(appContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                applyProtocol(TlsVersion.TLS_1_2.javaName())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }.build()

    private fun OkHttpClient.Builder.applyProtocol(protocolName: String) {
        val allTrustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(
                chain: Array<X509Certificate?>?,
                authType: String?
            ) {
            }

            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(
                chain: Array<out X509Certificate?>?,
                authType: String?
            ) {
            }
        }

        val sslContext = SSLContext.getInstance(protocolName).apply {
            init(null, arrayOf(allTrustManager), SecureRandom())
        }

        val sslSocketFactory = ProtocolSocketFactory(sslContext.socketFactory, protocolName)

        sslSocketFactory(sslSocketFactory, allTrustManager)
        hostnameVerifier { _, _ -> true }
    }

    internal class ProtocolSocketFactory constructor(
        private val sslSocketFactory: SSLSocketFactory,
        protocolName: String
    ) : SSLSocketFactory() {

        private val protocols = arrayOf(protocolName)

        override fun getDefaultCipherSuites(): Array<String> = sslSocketFactory.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> =
            sslSocketFactory.supportedCipherSuites

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket? =
            enableProtocolOnSocket(sslSocketFactory.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String, port: Int): Socket? =
            enableProtocolOnSocket(sslSocketFactory.createSocket(host, port))

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int
        ): Socket? = enableProtocolOnSocket(
            sslSocketFactory.createSocket(
                host,
                port,
                localHost,
                localPort
            )
        )

        override fun createSocket(host: InetAddress, port: Int): Socket? =
            enableProtocolOnSocket(sslSocketFactory.createSocket(host, port))

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): Socket? = enableProtocolOnSocket(
            sslSocketFactory.createSocket(
                address,
                port,
                localAddress,
                localPort
            )
        )

        private fun enableProtocolOnSocket(socket: Socket?): Socket? = socket?.also {
            if (it is SSLSocket) {
                it.enabledProtocols = protocols
            }
        }
    }
}