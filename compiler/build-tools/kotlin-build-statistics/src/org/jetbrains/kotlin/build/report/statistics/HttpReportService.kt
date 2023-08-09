/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.build.report.statistics

import com.google.gson.Gson
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import java.io.IOException
import java.io.Serializable
import java.lang.AutoCloseable
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

class HttpReportService(
    private val url: String,
    private val password: String?,
    private val user: String?,
    internal val useExecutor: Boolean = true,
) : Serializable {

    private var invalidUrl = false
    private var requestPreviousFailed = false

    private fun checkResponseAndLog(connection: HttpURLConnection, log: KotlinLogger) {
        val isResponseBad = connection.responseCode !in 200..299
        if (isResponseBad) {
            val message = "Failed to send statistic to ${connection.url} with ${connection.responseCode}: ${connection.responseMessage}"
            if (!requestPreviousFailed) {
                log.warn(message)
            } else {
                log.debug(message)
            }
            requestPreviousFailed = true
        }
    }

    //call via HttpReportServiceExecutor.sendData
    internal fun sendData(data: Any, log: KotlinLogger): Boolean {
        log.debug("Http report: send data $data")
        val elapsedTime = measureTimeMillis {
            if (invalidUrl) {
                return true
            }
            val connection = try {
                URL(url).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                log.warn("Http report: Unable to open connection to ${url}: ${e.message}")
                invalidUrl = true
                return true
            }

            try {
                if (user != null && password != null) {
                    val auth = Base64.getEncoder()
                        .encode("${user}:${password}".toByteArray())
                        .toString(Charsets.UTF_8)
                    connection.addRequestProperty("Authorization", "Basic $auth")
                }
                connection.addRequestProperty("Content-Type", "application/json")
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.outputStream.use {
                    it.write(Gson().toJson(data).toByteArray())
                }
                connection.connect()
                checkResponseAndLog(connection, log)
            } catch (e: Exception) {
                log.info("Http report: Unexpected exception happened: '${e.message}': ${e.stackTraceToString()}")
                return false
            } finally {
                connection.disconnect()
            }
        }
        log.debug("Report statistic by http takes $elapsedTime ms")
        return true
    }
}

//non-serializable part of HttpReportService
class HttpReportServiceExecutor : AutoCloseable {
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()
    private val retryQueue: ConcurrentLinkedQueue<Any> = ConcurrentLinkedQueue<Any>()
    override fun close() {
        //It's expected that bad internet connection can cause a significant delay for big project
        executorService.shutdown()
    }

    fun close(httpReportService: HttpReportService, log: KotlinLogger) {
        resentData(httpReportService, log)
        close()
    }

    private fun resentData(httpReportService: HttpReportService, log: KotlinLogger) {
        submit(httpReportService.useExecutor) {
            retryQueue.removeIf { httpReportService.sendData(it, log) }
        }
    }

    private fun submit(
        useExecutor: Boolean,
        action: () -> Unit,
    ) {
        if (useExecutor) {
            executorService.submit {
                action.invoke()
            }
        } else {
            action.invoke()
        }
    }

    fun sendData(
        httpReportService: HttpReportService,
        log: KotlinLogger,
        prepareData: () -> Any?,
    ) {
        submit(httpReportService.useExecutor) {
            val data = prepareData.invoke()
            if (data != null && !httpReportService.sendData(data, log)) {
                retryQueue.add(data)
            }
        }
    }

}
