package com.nightstand.photos.smart

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tflite.java.TfLite
import org.tensorflow.lite.InterpreterApi
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit

/**
 * MobileCLIP-S0 image encoder on LiteRT (TensorFlow Lite) from Google Play
 * services: the runtime is not bundled in the APK, and the model file is
 * downloaded on demand ([ModelStore]). Call only off the main thread.
 */
class TfLiteEmbedder private constructor(private val interpreter: InterpreterApi) : Embedder {
    private val inShape = interpreter.getInputTensor(0).shape()
    private val nhwc = inShape.last() == 3
    private val outDim = interpreter.getOutputTensor(0).shape().last()
    private val input = ByteBuffer.allocateDirect(4 * 3 * Preprocess.SIZE * Preprocess.SIZE).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(4 * outDim).order(ByteOrder.nativeOrder())

    override fun embed(thumb: Bitmap): FloatArray {
        val t = Preprocess.toTensor(Preprocess.pixels(thumb), nhwc)
        input.rewind(); input.asFloatBuffer().put(t)
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        return FloatArray(outDim).also { output.asFloatBuffer().get(it) }
    }

    override fun close() = interpreter.close()

    companion object {
        @Volatile private var runtimeReady = false

        /** Null if the model file is missing; throws if Play services' LiteRT can't start. */
        fun open(c: Context, model: File): TfLiteEmbedder? {
            if (!model.isFile) return null
            if (!runtimeReady) {
                Tasks.await(TfLite.initialize(c.applicationContext), 60, TimeUnit.SECONDS)
                runtimeReady = true
            }
            val buf = RandomAccessFile(model, "r").use { f -> f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()) }
            val opts = InterpreterApi.Options()
                .setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY)
                .setNumThreads(2)
            return TfLiteEmbedder(InterpreterApi.create(buf, opts))
        }
    }
}
