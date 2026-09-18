package com.intercept.data.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface InterceptApiService {
    @GET("health")
    suspend fun health(): Map<String, String>

    @POST("users/register")
    suspend fun register(@Body body: Map<String, String>): Map<String, String>

    @POST("calls/start")
    suspend fun startCall(@Body body: StartCallRequest): StartCallResponse

    @POST("calls/{id}/transcript")
    suspend fun sendTurn(@Path("id") id: String, @Body body: TurnRequest): TurnResponse

    @POST("calls/{id}/speak")
    suspend fun speak(@Path("id") id: String, @Body body: SpeakRequest): SpeakResponse

    @POST("calls/{id}/takeover")
    suspend fun takeover(@Path("id") id: String): Map<String, String>

    @POST("calls/{id}/end")
    suspend fun endCall(@Path("id") id: String): EndResponse

    @GET("calls/{id}/report")
    suspend fun report(@Path("id") id: String): ReportResponse

    @GET("app/latest")
    suspend fun latest(): LatestDto

    @POST("analyze/text")
    suspend fun analyzeText(@Body body: AnalyzeTextRequest): AnalyzeResponse

    @POST("analyze/screenshot")
    suspend fun analyzeScreenshot(@Body body: ScreenshotRequest): AnalyzeResponse

    @POST("analyze/url")
    suspend fun analyzeUrl(@Body body: UrlRequest): AnalyzeResponse

    @POST("analyze/qr")
    suspend fun analyzeQr(@Body body: QrRequest): AnalyzeResponse
}
